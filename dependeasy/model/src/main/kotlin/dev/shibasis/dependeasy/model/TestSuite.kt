package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@Serializable enum class Suite { Jvm, AndroidUnit, JavaScript, Hangar }

@Serializable enum class TestTag { Window, HangarFlow }

@Serializable enum class TestDiscovery { Conventional, Annotations }

@Serializable
data class ResolvedJvmTest(val className: String, val tags: Set<TestTag>, val source: SourceSite)

@Serializable
data class TestInput(val name: String, val environment: String, val default: String, val rerunWhen: String? = null)

@Serializable enum class TestDirectoryRoot { State, HostHome }

data class TestDirectory(val property: BuildOption<String>, val root: TestDirectoryRoot)

data class TestEnvironment(
    val classpathProperties: Set<String> = emptySet(),
    val inputs: List<TestInput> = emptyList(),
    val caseTimeoutMillis: Long? = null,
    val properties: List<BuildOption<*>> = emptyList(),
    val files: FileSet = FileSet(emptyList()),
    val state: BuildOption<String>? = null,
    val results: BuildOption<String>? = null,
    val preloads: FileSet = FileSet(emptyList()),
    val directories: List<TestDirectory> = emptyList(),
    val discovery: TestDiscovery = TestDiscovery.Conventional,
    val standardStreams: Boolean = false,
    val allowEmpty: Boolean = false,
) {
    init {
        require(directories.none { it.root == TestDirectoryRoot.State } || state != null) { "State directories need an isolated state" }
        val names = properties.map { it.name } + listOfNotNull(state?.name, results?.name) + directories.map { it.property.name }
        require(names.distinct().size == names.size && names.all(String::isNotBlank)) { "A test property is declared once" }
        require(classpathProperties.all(String::isNotBlank)) { "Test classpaths need their property names" }
        require(inputs.all { it.name.isNotBlank() && it.environment.isNotBlank() }) { "Test inputs need their names and environment variables" }
        require(inputs.map { it.name }.distinct().size == inputs.size) { "A test input is declared once" }
        require(caseTimeoutMillis == null || caseTimeoutMillis > 0) { "A case timeout must be positive" }
    }
}

@Serializable
data class TestEnvironmentSpec(
    val classpathProperties: Set<String> = emptySet(),
    val inputs: List<TestInput> = emptyList(),
    val caseTimeoutMillis: Long? = null,
    val properties: List<OptionValue> = emptyList(),
    val files: FileSet = FileSet(emptyList()),
    val state: OptionValue? = null,
    val results: OptionValue? = null,
    val preloads: FileSet = FileSet(emptyList()),
    val directories: Map<TestDirectoryRoot, List<OptionValue>> = emptyMap(),
    val discovery: TestDiscovery = TestDiscovery.Conventional,
    val standardStreams: Boolean = false,
    val allowEmpty: Boolean = false,
    val resolvedInputs: Map<String, OptionValue> = emptyMap(),
)

object TestEnvironmentKernel {
    fun plan(spec: TestEnvironmentSpec, build: File, project: File, hostHome: File? = null): Map<String, String> {
        val properties = spec.properties.associate { it.name to it.value }.toMutableMap()
        val boundary = build.toPath().toAbsolutePath().normalize()
        val state = spec.state?.let {
            val relative = Path.of(it.value)
            val path = boundary.resolve(relative).normalize()
            require(!relative.isAbsolute && path != boundary && path.startsWith(boundary)) { "Test state escapes build directory" }
            properties[it.name] = path.toString()
            path
        }
        spec.results?.let { properties[it.name] = project.resolve(it.value).absolutePath }
        spec.directories.forEach { (root, values) ->
            val base = when (root) {
                TestDirectoryRoot.State -> requireNotNull(state)
                TestDirectoryRoot.HostHome -> requireNotNull(hostHome).toPath().toAbsolutePath().normalize()
            }
            values.forEach {
                val relative = Path.of(it.value)
                val path = base.resolve(relative).normalize()
                require(!relative.isAbsolute && path.startsWith(base)) { "Test directory escapes $root: ${it.value}" }
                properties[it.name] = path.toString()
            }
        }
        return properties.toMap()
    }

    fun prepare(directory: File, within: File, children: List<String>) {
        val boundary = within.toPath().toAbsolutePath().normalize()
        val target = directory.toPath().toAbsolutePath().normalize()
        for (path in listOf(boundary, target)) for (ancestor in generateSequence(path) { it.parent }) {
            val systemAlias = ancestor in setOf(Path.of("/var"), Path.of("/tmp")) &&
                runCatching { ancestor.toRealPath() == Path.of("/private").resolve(ancestor.fileName) }.getOrDefault(false)
            require(!Files.isSymbolicLink(ancestor) || systemAlias) { "Refusing test state through symbolic link $ancestor" }
        }
        val allowed = within.canonicalFile.toPath()
        val actual = directory.canonicalFile.toPath()
        require(boundary.parent != null && actual != allowed && actual.startsWith(allowed)) { "Test state escapes build directory" }
        val created = children.map { child ->
            val relative = Path.of(child)
            target.resolve(relative).normalize().also {
                require(!relative.isAbsolute && it.startsWith(target)) { "Test directory escapes state: $child" }
            }
        }
        if (Files.exists(target)) Files.walk(target).use { paths ->
            paths.filter(Files::isSymbolicLink).forEach { link ->
                require(runCatching { link.toRealPath().startsWith(allowed.toRealPath()) }.getOrDefault(false)) {
                    "Test state link escapes build directory: $link"
                }
            }
        }
        if (Files.exists(target)) Files.walk(target).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        Files.createDirectories(target)
        created.forEach(Files::createDirectories)
    }
}
