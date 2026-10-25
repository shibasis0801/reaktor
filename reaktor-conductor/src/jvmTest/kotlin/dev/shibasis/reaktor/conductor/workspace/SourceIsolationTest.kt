package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SourceIsolationTest {
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { result }
        return result.trim()
    }

    private fun fixture(body: (File, File, LocalAgentArtifacts, AgentWorktrees) -> Unit) {
        val workspace = Files.createTempDirectory("source-isolation").toFile()
        val root = File(workspace, "project").apply { mkdir() }
        val data = File(workspace, "data").apply { mkdir() }
        val artifacts = LocalAgentArtifacts(File(data, "artifacts").toPath())
        try {
            git(root, "init", "--quiet")
            File(root, "Source.kt").writeText("val value = 1\n")
            git(root, "add", ".")
            git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Baseline")
            body(root, data, artifacts, AgentWorktrees(root, File(data, "worktrees").toPath(), artifacts))
        } finally { workspace.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun snapshotsKeepAuthoredDirtyInputsAndExcludeRuntimeAndDatedEvidence() = fixture { root, _, artifacts, store ->
        fun write(name: String, value: String) = File(root, name).apply { parentFile.mkdirs(); writeText(value) }
        write("agent/audit-20261025/screen.png", "old evidence")
        write(".reaktor/libexec/runtime.jar", "old runtime")
        git(root, "add", ".")
        git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Evidence baseline")
        val index = File(root, ".git/index").readBytes()
        write("Source.kt", "val value = 2\n")
        write("modules/assets/icon.png", "authored asset")
        write("agent/config.json", "{\"authored\":true}")
        write("agent/audit-20261025/probe.py", "print('authored probe')")
        write("agent/audit-20261025/screen.png", "new evidence")
        write(".reaktor/libexec/runtime.jar", "new runtime")
        repeat(250) { write("reports/capture-20261025/$it.json", "{\"generated\":true}") }
        val nested = File(root, ".claude/worktrees/other").apply { mkdirs() }
        git(nested, "init", "--quiet")
        File(nested, "Other.kt").writeText("val other = 1")
        val candidates = SourceCandidates(root, artifacts)
        val before = candidates.capture()
        assertTrue(before.complete, before.notices.toString())
        assertEquals(setOf("Source.kt", "modules/assets/icon.png", "agent/config.json", "agent/audit-20261025/probe.py"), before.changedFiles.toSet())
        assertFalse("new evidence" in artifacts.text(before.diff!!))
        assertEquals(before.changedFiles.toSet(), candidates.dirtyManifest().entries.keys)
        write("agent/audit-20261025/screen.png", "moving evidence again")
        assertEquals(before.sourceDigest, candidates.capture().sourceDigest)
        val worktree = store.prepare("source-inputs", "writer")
        val checkout = File(worktree.roots.single().path)
        assertEquals("val value = 2\n", File(checkout, "Source.kt").readText())
        assertEquals("authored asset", File(checkout, "modules/assets/icon.png").readText())
        assertEquals("old evidence", File(checkout, "agent/audit-20261025/screen.png").readText())
        assertEquals("old runtime", File(checkout, ".reaktor/libexec/runtime.jar").readText())
        assertFalse(File(checkout, "reports/capture-20261025/0.json").exists())
        assertFalse(File(checkout, ".claude/worktrees/other").exists())
        assertContentEquals(index, File(root, ".git/index").readBytes())
        store.remove(worktree.id)
    }

    @Test fun abandonmentRetainsEveryIncludedRootAndRefusesAChangedOrIncompleteSnapshot() = fixture { root, _, artifacts, store ->
        val framework = File(root.parentFile, "framework").apply { mkdir() }
        git(framework, "init", "--quiet")
        File(framework, "Framework.kt").writeText("val framework = 1\n")
        git(framework, "add", ".")
        git(framework, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Framework")
        File(root, "settings.gradle.kts").writeText("includeBuild(\"../framework\")")
        File(root, ".gitignore").writeText("build/\n")
        File(root, "build/dependeasy/model.json").apply { parentFile.mkdirs(); writeText(
            """{"schema":4,"producer":{"version":"fixture"},"builds":[{"name":"project","directory":"."},{"name":"framework","directory":"../framework"}]}""") }
        git(root, "add", ".")
        git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Include")
        val tree = store.prepare("abandoned-roots", "writer")
        assertEquals(2, tree.roots.size)
        val indices = listOf(root, framework).associateWith { File(it, ".git/index").readBytes() }
        tree.roots.forEach { File(it.path, "New.kt").writeText("val retained = \"${File(it.source).name}\"\n") }
        val snapshot = store.retainSnapshot(tree.id)
        val manifest = dev.shibasis.reaktor.conductor.ConductorJson.parseToJsonElement(artifacts.text(snapshot)).jsonObject
        val missingRoot = artifacts.put(kotlinx.serialization.json.JsonObject(manifest.toMutableMap().apply {
            put("roots", kotlinx.serialization.json.JsonArray(manifest.getValue("roots").jsonArray.take(1)))
        }).toString(), "worktree-snapshot")
        assertFailsWith<IllegalArgumentException> { store.abandon(tree.id, missingRoot) }
        assertTrue(tree.roots.all { File(it.path, "New.kt").exists() })
        val moved = File(tree.roots.first().path, "New.kt")
        val saved = moved.readText()
        moved.appendText("val later = true\n")
        assertFailsWith<IllegalArgumentException> { store.abandon(tree.id, snapshot) }
        assertTrue(tree.roots.all { File(it.path).exists() })
        moved.writeText(saved)
        store.abandon(tree.id, snapshot)
        assertTrue(tree.roots.all { !File(it.path).exists() })
        manifest.getValue("roots").jsonArray.forEach { item ->
            val entry = item.jsonObject
            val source = File(entry.getValue("source").jsonPrimitive.content)
            val ref = entry.getValue("ref").jsonPrimitive.content
            assertEquals("val retained = \"${source.name}\"", git(source, "show", "$ref:New.kt"))
        }
        indices.forEach { (source, index) -> assertContentEquals(index, File(source, ".git/index").readBytes()) }
    }

    @Test fun snapshotWriteFailureNeverRemovesTheOwnedCheckout() = fixture { root, _, _, store ->
        val tree = store.prepare("failed-snapshot", "writer")
        File(tree.roots.single().path, "Untracked.kt").writeText("val recover = true\n")
        File(root, ".git/refs/reaktor").mkdirs()
        File(root, ".git/refs/reaktor/abandoned").writeText("reference namespace blocked")
        assertFailsWith<Exception> { store.retainSnapshot(tree.id) }
        assertTrue(File(tree.roots.single().path, "Untracked.kt").exists())
        assertEquals(tree, store.read(tree.id))
    }

    @Test fun movingCaptureAndMovingSnapshotRetryAndDiscardTheOldTrial() = fixture { root, _, artifacts, store ->
        val index = File(root, ".git/index").readBytes()
        var captures = 0
        val worktree = store.prepare("moving", "writer") {
            captures++
            if (captures == 3) File(root, "Source.kt").writeText("val value = 3\n")
            SourceCandidates(root, artifacts).capture().let {
                if (captures == 1) it.copy(complete = false, notices = listOf("Source changed during capture: Source.kt")) else it
            }
        }
        assertEquals(5, captures)
        assertEquals("val value = 3\n", File(worktree.roots.single().path, "Source.kt").readText())
        assertEquals(2, git(root, "worktree", "list", "--porcelain").lineSequence().count { it.startsWith("worktree ") })
        assertContentEquals(index, File(root, ".git/index").readBytes())
        store.remove(worktree.id)
        assertTrue(store.list("moving").isEmpty())
        assertEquals(1, git(root, "worktree", "list", "--porcelain").lineSequence().count { it.startsWith("worktree ") })
    }

    @Test fun persistentSourceMotionStopsAfterThreeAttemptsAndLeavesNoTrials() = fixture { root, data, artifacts, store ->
        var captures = 0
        val failure = assertFailsWith<IllegalStateException> {
            store.prepare("always-moving", "writer") {
                captures++
                if (captures % 2 == 0) File(root, "Source.kt").writeText("val value = $captures\n")
                SourceCandidates(root, artifacts).capture()
            }
        }
        assertTrue("three isolation attempts" in failure.message.orEmpty())
        assertEquals(6, captures)
        assertTrue(File(data, "worktrees").listFiles().orEmpty().isEmpty())
        assertEquals(1, git(root, "worktree", "list", "--porcelain").lineSequence().count { it.startsWith("worktree ") })
    }

    @Test fun nestedIncludedBuildsAreCoveredByTheirOwningRepository() = fixture { root, _, artifacts, store ->
        val framework = File(root.parentFile, "framework").apply { mkdir() }
        git(framework, "init", "--quiet")
        listOf(root, framework).forEach { repository ->
            File(repository, ".gitignore").writeText("build/\n")
            File(repository, "settings.gradle.kts").writeText("includeBuild(\"plugin\")")
            File(repository, "plugin").mkdir()
            File(repository, "plugin/settings.gradle.kts").writeText("includeBuild(System.getenv(\"DYNAMIC_BUILD\"))")
            File(repository, "plugin/Plugin.kt").writeText("val plugin = 1\n")
            git(repository, "add", ".")
            git(repository, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Included build")
        }
        File(root, "build/dependeasy/model.json").apply { parentFile.mkdirs(); writeText(
            """{"schema":4,"producer":{"version":"fixture"},"builds":[{"name":"project","directory":"."},{"name":"plugin","directory":"plugin"},{"name":"framework","directory":"../framework"},{"name":"framework-plugin","directory":"../framework/plugin"}]}""") }
        File(framework, "build/dependeasy/model.json").apply { parentFile.mkdirs(); writeText(
            """{"schema":4,"producer":{"version":"fixture"},"builds":[{"name":"framework","directory":"."},{"name":"plugin","directory":"plugin"}]}""") }
        File(framework, "plugin/Plugin.kt").writeText("val plugin = 2\n")
        val candidate = SourceCandidates(root, artifacts).capture()
        assertTrue(candidate.complete, candidate.notices.toString())
        assertEquals(setOf(root.canonicalPath, framework.canonicalPath), candidate.sourceRoots.toSet())
        val worktree = store.prepare("included-builds", "writer")
        assertEquals(2, worktree.roots.size)
        assertEquals("val plugin = 2\n", File(worktree.roots.single { it.source == framework.canonicalPath }.path, "plugin/Plugin.kt").readText())
        store.remove(worktree.id)
    }

    @Test fun gitlinksRetainTheirPinnedRevisionAndDirtySubmoduleSourceRefusesIsolation() = fixture { root, _, artifacts, store ->
        val library = File(root.parentFile, "library").apply { mkdir() }
        git(library, "init", "--quiet")
        File(library, "Library.kt").writeText("val library = 1\n")
        git(library, "add", ".")
        git(library, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Library baseline")
        val vendor = File(root, "vendor/library").apply { parentFile.mkdirs() }
        git(library, "worktree", "add", "--detach", vendor.path, "HEAD")
        git(root, "add", "vendor/library")
        git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Pin library")
        val candidates = SourceCandidates(root, artifacts)
        val initial = candidates.capture()
        assertTrue(initial.complete, initial.notices.toString())
        assertEquals(listOf(root.canonicalPath), initial.sourceRoots)
        File(vendor, "Library.kt").writeText("val library = 2\n")
        val dirty = candidates.capture()
        assertFalse(dirty.complete)
        assertTrue(dirty.notices.any { "Submodule source is not clean: vendor/library" in it })
        assertFailsWith<IllegalArgumentException> { store.prepare("dirty-library", "writer") }
        git(vendor, "add", ".")
        git(vendor, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Library update")
        val updated = candidates.capture()
        assertTrue(updated.complete, updated.notices.toString())
        assertNotEquals(initial.sourceDigest, updated.sourceDigest)
        val worktree = store.prepare("pinned-library", "writer")
        val checkout = File(worktree.roots.single().path)
        assertTrue(git(checkout, "ls-files", "--stage", "vendor/library").contains(git(vendor, "rev-parse", "HEAD")))
        assertTrue(SourceCandidates(checkout, artifacts).capture().complete)
        store.remove(worktree.id)
        git(library, "worktree", "remove", "--force", vendor.path)
    }

    @Test fun incompleteCoverageAfterSnapshotRefusesAndRemovesTheTrial() = fixture { root, data, artifacts, store ->
        var captures = 0
        assertFailsWith<IllegalArgumentException> {
            store.prepare("incomplete", "writer") {
                captures++
                SourceCandidates(root, artifacts).capture().let {
                    if (captures == 2) it.copy(complete = false, notices = listOf("New-file diff budget exceeded")) else it
                }
            }
        }
        assertEquals(2, captures)
        assertTrue(File(data, "worktrees").listFiles().orEmpty().isEmpty())
        assertEquals(1, git(root, "worktree", "list", "--porcelain").lineSequence().count { it.startsWith("worktree ") })
    }
}
