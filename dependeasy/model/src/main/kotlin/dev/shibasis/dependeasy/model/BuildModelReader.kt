package dev.shibasis.dependeasy.model

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files

object BuildModelReader {
    const val RelativePath = "build/dependeasy/model.json"
    const val MaxBytes = 8 * 1024 * 1024
    val json = Json { encodeDefaults = true }

    fun decode(text: String): BuildModel {
        require(text.toByteArray(Charsets.UTF_8).size <= MaxBytes) { "Build model exceeds $MaxBytes bytes" }
        val document = json.parseToJsonElement(text).jsonObject
        val schema = document["schema"]?.jsonPrimitive?.intOrNull
        require(schema == 3) { "Unsupported build model schema $schema; exportBuildModel must be rerun" }
        val model = json.decodeFromJsonElement<BuildModel>(document)
        require(model.builds.map { it.name }.distinct().size == model.builds.size) { "Duplicate build identity" }
        require(model.nodes.map { it.id }.distinct().size == model.nodes.size) { "Duplicate build node identity" }
        val builds = model.builds.map { it.name }.toSet()
        require(model.nodes.all { it.id.build in builds }) { "Unknown node build" }
        require(model.declarations.all { it.build in builds }) { "Unknown declaration build" }
        require(model.declarations.all { (it.kind == DeclarationInputKind.Option) == (it.option != null) }) { "Option declaration needs its recorded evidence" }
        return model
    }

    fun read(root: File): BuildModel {
        val model = declaredModel(root)
        val stale = changedDeclarations(root, model)
        require(stale.isEmpty()) { "Build model is stale: ${stale.size} declaration inputs changed; rerun exportBuildModel" }
        return model
    }

    fun readAllowingStale(root: File): BuildModel = declaredModel(root)

    private fun declaredModel(root: File): BuildModel {
        val file = root.resolve(RelativePath)
        require(file.isFile) { "No build model yet. Run ./gradlew exportBuildModel in ${root.path}" }
        val bytes = file.inputStream().use { it.readNBytes(MaxBytes + 1) }
        require(bytes.size <= MaxBytes) { "Build model exceeds $MaxBytes bytes" }
        val model = decode(bytes.toString(Charsets.UTF_8))
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile }
        model.declarations.filter { it.kind != DeclarationInputKind.Option }.forEach { input ->
            val owner = directories.getValue(input.build)
            require(owner.resolve(input.path).canonicalFile.toPath().startsWith(owner.toPath())) { "Declaration escapes its build" }
        }
        return model
    }

    fun changedDeclarations(root: File, model: BuildModel): List<DeclarationInput> {
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile }
        return model.declarations.filter { it.kind != DeclarationInputKind.Option }.filter { input ->
            val owner = directories.getValue(input.build)
            val declaration = owner.resolve(input.path).canonicalFile
            require(declaration.toPath().startsWith(owner.toPath())) { "Declaration escapes its build" }
            when (input.kind) {
                DeclarationInputKind.File -> !declaration.isFile || digest(declaration) != input.sha256
                DeclarationInputKind.Membership -> BuildDeclarationInputs.membership(declaration) != input.sha256
                DeclarationInputKind.Option -> false
            }
        }
    }


    fun affected(root: File, since: String, view: String = "nodes", offset: Int = 0, limit: Int = 10): JsonObject {
        require(since.isNotBlank() && !since.startsWith('-') && since.none(Char::isISOControl)) { "Expected one Git commit reference" }
        require(offset >= 0 && limit in 1..10)
        require(view in setOf("nodes", "changes", "omissions", "revisions")) { "Expected nodes, changes, omissions or revisions" }
        val model = declaredModel(root)
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile }
        val omissions = model.omissions.map { json.encodeToJsonElement(it).jsonObject }.toMutableList()
        fun omission(node: NodeId?, reason: String, reference: String) {
            omissions += buildJsonObject { node?.let { put("node", it.toString()) }; put("reason", reason); put("reference", reference) }
        }
        val revisions = mutableListOf<JsonObject>()
        val changes = linkedMapOf<Pair<String, String>, java.nio.file.Path>()
        for ((build, directory) in directories) {
            val repository = runCatching { affectedGit(directory, listOf("rev-parse", "--show-toplevel")).trim() }.getOrNull()
            if (repository == null || File(repository).canonicalFile != directory) {
                omission(null, "Declared build has no independent readable Git repository", build)
                continue
            }
            val base = affectedGit(directory, listOf("rev-parse", "--verify", "--end-of-options", "$since^{commit}")).trim()
            require(base.matches(Regex("[0-9a-f]{40,64}"))) { "Git reference did not resolve to one commit in $build" }
            revisions += buildJsonObject { put("build", build); put("since", base); put("head", affectedGit(directory, listOf("rev-parse", "HEAD")).trim()) }
            val names = affectedGit(directory, listOf("diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", base, "--")) +
                affectedGit(directory, listOf("ls-files", "--others", "--exclude-standard", "-z"))
            for (name in names.split('\u0000').filter(String::isNotEmpty).distinct().sorted()) {
                val path = directory.toPath().resolve(name).normalize()
                require(path.startsWith(directory.toPath())) { "Git path escapes $build" }
                changes[build to name] = path
            }
        }
        val reasons = linkedMapOf<NodeId, MutableList<JsonObject>>()
        val matched = mutableSetOf<Pair<String, String>>()
        fun select(id: NodeId, reason: String, change: Pair<String, String>? = null, via: NodeId? = null) {
            val evidence = buildJsonObject {
                put("id", id.toString()); put("reason", reason)
                change?.let { put("build", it.first); put("path", it.second) }
                via?.let { put("via", it.toString()) }
            }
            reasons.getOrPut(id) { mutableListOf() }.also { if (evidence !in it) it += evidence }
            change?.let(matched::add)
        }
        val declaredFiles = model.declarations.filter { it.kind == DeclarationInputKind.File }.map {
            directories.getValue(it.build).resolve(it.path).toPath().normalize()
        }.toSet()
        val declarationChanges = changes.filterValues { it in declaredFiles }.keys
        for (change in declarationChanges) model.nodes.forEach { select(it.id, "Build declaration changed; conservatively select the composite", change) }
        for (node in model.nodes) {
            val owner = directories.getValue(node.id.build).toPath()
            val inputs = node.inputs.flatMap { files -> files.paths.map { FileInput(FileInputKind.File, it) } + files.declarations }
            if (inputs.isEmpty() && model.edges.none { it.from == node.id && it !is Edge.OrderedAfter })
                omission(node.id, "No declared file input coverage for this leaf", node.id.toString())
            for (input in inputs) {
                if (input.kind !in setOf(FileInputKind.File, FileInputKind.Tree)) {
                    omission(node.id, "Input identity needs its producer or configuration to resolve", input.identity)
                    continue
                }
                val path = owner.resolve(input.identity).normalize()
                if (input.includes.isNotEmpty() || input.excludes.isNotEmpty())
                    omission(node.id, "Tree filters conservatively select the whole declared tree", input.identity)
                for ((change, changed) in changes) if (changed == path || changed.startsWith(path)) {
                    select(node.id, if (input.kind == FileInputKind.Tree) "Changed path is in a declared input tree" else "Changed path is a declared input", change)
                }
            }
        }
        val known = model.nodes.mapTo(hashSetOf()) { it.id }
        val consumers = model.edges.filter { it !is Edge.OrderedAfter }.groupBy { it.to }
        val queue = ArrayDeque(reasons.keys)
        val visited = mutableSetOf<NodeId>()
        while (queue.isNotEmpty()) {
            val producer = queue.removeFirst()
            if (!visited.add(producer)) continue
            for (edge in consumers[producer].orEmpty()) if (edge.from in known) {
                select(edge.from, "Depends on an affected node through " + when (edge) {
                    is Edge.Includes -> "Includes"
                    is Edge.Consumes -> "Consumes"
                    is Edge.After -> "After"
                    is Edge.OrderedAfter -> error("Ordering is not a dependency")
                }, via = producer)
                queue.addLast(edge.from)
            }
        }
        val changeRows = changes.keys.map { (build, path) -> buildJsonObject {
            put("build", build); put("path", path); put("matched", (build to path) in matched)
        } }
        val rows = when (view) {
            "nodes" -> reasons.toSortedMap(compareBy(NodeId::toString)).values.flatten()
            "changes" -> changeRows
            "omissions" -> omissions.distinct()
            else -> revisions
        }
        return buildJsonObject {
            put("truth", if (changedDeclarations(root, model).isEmpty()) "source" else "stale")
            put("selection", "conservative"); put("reuseAllowed", false)
            put("since", since); put("modelDigest", digest(encode(model)))
            put("selectedNodeCount", reasons.size); put("changedPathCount", changes.size)
            put("unmatchedChangeCount", changes.keys.count { it !in matched })
            put("omissionCount", omissions.distinct().size)
            put("coverage", "partial")
            put("coverageNote", "Declared inputs and dependency edges only; unresolved inputs, tree filters and unmatched changes remain explicit. This selection does not authorize receipt reuse.")
            put("view", view); put("offset", offset); put("total", rows.size)
            put(view, JsonArray(rows.drop(offset).take(limit)))
            if (offset + limit < rows.size) put("nextOffset", offset + limit)
        }
    }

    private fun affectedGit(root: File, arguments: List<String>): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["GIT_OPTIONAL_LOCKS"] = "0" }.start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.use { it.readNBytes(MaxBytes + 1) }
        }
        try {
            check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { "Git inspection timed out in ${root.name}" }
            val bytes = output.get(1, java.util.concurrent.TimeUnit.SECONDS)
            require(process.exitValue() == 0) { "Cannot inspect Git reference or changes in ${root.name}; verify --since in that declared build" }
            require(bytes.size <= MaxBytes) { "Git change inventory exceeds $MaxBytes bytes" }
            return bytes.toString(Charsets.UTF_8)
        } finally { process.destroyForcibly() }
    }

    fun node(root: File, id: String): Node = read(root).nodes.single { it.id.toString() == id }
    fun nodeDetails(root: File, id: String, collection: String? = null, offset: Int = 0, limit: Int = 10): JsonObject {
        require(offset >= 0 && limit in 1..10)
        val model = declaredModel(root)
        val changed = changedDeclarations(root, model)
        val selected = model.nodes.single { it.id.toString() == id }
        val details = buildJsonObject {
            put("truth", if (changed.isEmpty()) "source" else "stale")
            put("stale", changed.isNotEmpty())
            put("changedDeclarations", buildJsonArray { changed.forEach { add("${it.build}:${it.path}") } })
            put("omissionCounts", json.encodeToJsonElement(omissionCounts(model)))
            put("node", json.encodeToJsonElement(Node.serializer(), selected))
            put("effectiveHost", json.encodeToJsonElement(HostRequirement.serializer(), model.requiredHost(selected.id)))
            if (selected.kernel?.spec is KernelSpec.WranglerDeploy) {
                val spec = selected.kernel.spec.deployment
                val owner = root.resolve(model.builds.single { it.name == selected.id.build }.directory)
                if (WorkerDeployKernel.planFile(owner, selected.id, spec.environment).isFile)
                    put("deploymentPlan", json.encodeToJsonElement(WorkerDeployKernel.retained(owner, selected.id, spec.environment)))
                put("deploymentReceipts", json.encodeToJsonElement(WorkerDeployKernel.receipts(owner, selected.id)))
            }
            put("edges", buildJsonArray { model.edges.filter { it.from == selected.id || it.to == selected.id }
                .forEach { add(json.encodeToJsonElement(Edge.serializer(), it)) } })
            put("omissions", buildJsonArray { model.omissions.filter { it.node == null || it.node == selected.id }
                .forEach { add(json.encodeToJsonElement(Omission.serializer(), it)) } })
        }
        val collections = mutableListOf<JsonElement>()
        fun bounded(value: JsonElement, path: String): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) -> bounded(child, "$path/${key.replace("~", "~0").replace("/", "~1")}") })
            is JsonArray -> {
                if (value.size > limit) collections += buildJsonObject {
                    put("path", path); put("total", value.size); put("nextOffset", limit)
                }
                JsonArray(value.take(limit).mapIndexed { index, child -> bounded(child, "$path/$index") })
            }
            else -> value
        }
        if (collection == null) {
            require(offset == 0) { "An offset requires a collection path" }
            val page = bounded(details, "").jsonObject
            return JsonObject(page + ("collections" to JsonArray(collections)))
        }
        require(collection.startsWith('/')) { "Expected a JSON pointer from collections" }
        val value = collection.drop(1).split('/').fold(details as JsonElement) { parent, part ->
            val key = part.replace("~1", "/").replace("~0", "~")
            when (parent) {
                is JsonObject -> parent.getValue(key)
                is JsonArray -> parent[key.toInt()]
                else -> error("Collection path has no child")
            }
        }
        require(value is JsonArray) { "Collection path must select an array" }
        val items = value.drop(offset).take(limit).mapIndexed { index, child -> bounded(child, "$collection/${offset + index}") }
        return buildJsonObject {
            put("truth", if (changed.isEmpty()) "source" else "stale"); put("stale", changed.isNotEmpty())
            put("nodeId", id); put("collection", collection)
            put("offset", offset); put("total", value.size); put("items", JsonArray(items))
            put("collections", JsonArray(collections))
            if (offset + limit < value.size) put("nextOffset", offset + limit)
        }
    }

    fun modelPage(root: File, view: String = "nodes", offset: Int = 0, limit: Int = 5): JsonObject {
        require(offset >= 0 && limit in 1..10)
        val model = declaredModel(root)
        val changed = changedDeclarations(root, model)
        val values = when (view) {
            "nodes" -> model.nodes.map { node ->
                val encoded = json.encodeToJsonElement(Node.serializer(), node).jsonObject
                JsonObject(encoded.filterKeys { it in setOf("id", "kind", "title", "label", "effect", "host", "declaredAt") })
            }
            "edges" -> model.edges.map { json.encodeToJsonElement(Edge.serializer(), it) }
            else -> error("Expected nodes or edges")
        }
        return buildJsonObject {
            put("truth", if (changed.isEmpty()) "source" else "stale"); put("schema", model.schema)
            put("stale", changed.isNotEmpty()); put("changedDeclarationCount", changed.size)
            put("changedDeclarations", buildJsonArray { changed.take(limit).forEach { add("${it.build}:${it.path}") } })
            if (changed.size > limit) put("omission", "Read nodeDetails collection /changedDeclarations for all changed declaration inputs")
            put("omissionCounts", json.encodeToJsonElement(omissionCounts(model)))
            put("producer", json.encodeToJsonElement(Producer.serializer(), model.producer))
            put("builds", buildJsonArray { model.builds.forEach { add(json.encodeToJsonElement(BuildIdentity.serializer(), it)) } })
            put("view", view); put("offset", offset); put("total", values.size)
            put(view, JsonArray(values.drop(offset).take(limit)))
            if (offset + limit < values.size) put("nextOffset", offset + limit)
        }
    }

    fun omissionCounts(model: BuildModel): Map<String, Int> = model.omissions.groupingBy { it.reason }.eachCount().toSortedMap()

    private fun status(root: File, model: BuildModel): JsonObject {
        val changed = changedDeclarations(root, model)
        return buildJsonObject {
            put("truth", if (changed.isEmpty()) "source" else "stale"); put("stale", changed.isNotEmpty())
            put("changedDeclarationCount", changed.size)
            put("changedDeclarations", buildJsonArray { changed.take(10).forEach { add("${it.build}:${it.path}") } })
            put("omissionCounts", json.encodeToJsonElement(omissionCounts(model)))
        }
    }

    private fun selected(model: BuildModel, selector: String?): List<Node> {
        if (selector == null) return model.nodes.filter { it.id.build == model.builds.single { build -> build.directory == "." }.name }
        model.nodes.singleOrNull { it.id.toString() == selector }?.let { return listOf(it) }
        val own = model.builds.single { it.directory == "." }.name
        val build = if (selector.startsWith(':')) own else selector.substringBefore(':')
        val project = if (selector.startsWith(':')) selector else selector.substringAfter(build).ifEmpty { ":" }
        return model.nodes.filter { it.id.build == build && it.id.project == project }.also {
            require(it.isNotEmpty()) { "Unknown build node or project: $selector" }
        }
    }

    private fun summary(model: BuildModel, node: Node): JsonObject = buildJsonObject {
        put("id", node.id.toString()); put("kind", node.kind.name); put("label", node.label); put("effect", node.effect.name)
        put("declaredAt", node.declaredAt?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        put("kernel", node.kernel?.let { buildJsonObject {
            put("className", it.className); put("source", it.source?.let { source -> json.encodeToJsonElement(source) } ?: JsonNull)
            put("spec", it.spec?.let { spec -> buildJsonObject {
                val fields = json.encodeToJsonElement(KernelSpec.serializer(), spec).jsonObject
                put("type", fields.getValue("type")); put("fields", JsonArray(fields.keys.filter { key -> key != "type" }.map(::JsonPrimitive)))
            } } ?: JsonNull)
        } } ?: JsonNull)
        put("host", json.encodeToJsonElement(node.host)); put("effectiveHost", json.encodeToJsonElement(model.requiredHost(node.id)))
        val spec = node.kernel?.spec?.let { json.encodeToJsonElement(KernelSpec.serializer(), it).jsonObject }
        put("run", spec?.get("run") ?: JsonNull)
        put("tools", json.encodeToJsonElement(node.tools))
    }

    fun tree(root: File, selector: String? = null, depth: Int = 2, offset: Int = 0, limit: Int = 10, expand: Boolean = false): JsonObject {
        require(depth in 0..8 && offset >= 0 && limit in 1..20)
        val model = declaredModel(root)
        val status = status(root, model)
        val byId = model.nodes.associateBy { it.id }
        val outgoing = model.edges.groupBy { it.from }
        val chosen = selected(model, selector)
        val chosenIds = chosen.map { it.id }.toSet()
        val included = model.edges.filter { it.from in chosenIds }.map { it.to }.toSet()
        val roots = if (chosen.size == 1) chosen else chosen.filter { it.id !in included }.ifEmpty { chosen.take(1) }
        var remaining = 200
        fun branch(node: Node, level: Int, ancestors: Set<NodeId>): JsonObject {
            remaining--
            val related = outgoing[node.id].orEmpty()
            val cycle = node.id in ancestors
            val children = mutableListOf<JsonElement>()
            if (!cycle && level > 0) for (edge in related.take(limit)) {
                if (remaining <= 0) break
                children += buildJsonObject {
                    put("relationship", json.encodeToJsonElement(Edge.serializer(), edge))
                    val target = byId[edge.to]
                    if (target == null) { put("id", edge.to.toString()); put("omission", "Referenced producer has no declaration model") }
                    else put("node", branch(target, level - 1, ancestors + node.id))
                }
            }
            return buildJsonObject {
                summary(model, node).forEach { (key, value) -> put(key, value) }
                put("children", JsonArray(children)); put("more", related.size - children.size)
                if (cycle) put("cycle", true)
                if (expand) {
                    if (status.getValue("stale").jsonPrimitive.boolean) put("planOmission", "A stale declaration cannot reuse a retained Gradle plan")
                    else {
                        val plan = retainedPlan(root, node.id.toString())
                        if (plan == null) put("planOmission", "No retained exact Gradle plan; request build plan ${node.id} to configure Gradle explicitly")
                        else put("gradlePlan", planPage(plan))
                    }
                }
            }
        }
        val page = buildJsonArray {
            for (node in roots.drop(offset).take(limit)) {
                if (remaining <= 0) break
                add(branch(node, depth, emptySet()))
            }
        }
        return buildJsonObject {
            status.forEach { (key, value) -> put(key, value) }
            put("selector", selector?.let(::JsonPrimitive) ?: JsonNull); put("depth", depth); put("offset", offset); put("totalRoots", roots.size)
            put("roots", page)
            if (offset + page.size < roots.size) put("nextOffset", offset + page.size)
            put("nodeBudget", 200); put("expanded", expand)
        }
    }

    fun explain(root: File, id: String, offset: Int = 0, limit: Int = 10): JsonObject {
        require(offset >= 0 && limit in 1..20)
        val model = declaredModel(root)
        val node = model.nodes.single { it.id.toString() == id }
        val spec = node.kernel?.spec
        val fields = spec?.let { json.encodeToJsonElement(KernelSpec.serializer(), it).jsonObject.filterKeys { key -> key != "type" } }.orEmpty()
        val options = model.buildOptions(node.id)
        val nonDefaults = spec?.let { Json(json) { encodeDefaults = false }.encodeToJsonElement(KernelSpec.serializer(), it).jsonObject }.orEmpty()
        val definition = spec?.let {
            model.builds.firstNotNullOfOrNull { build ->
                val owner = root.resolve(build.directory)
                JvmEntry.sourceSite(it.javaClass, owner, build.name, listOf(owner.resolve("dependeasy/model/src/main/kotlin")))
            }
        }
        val bounded = ((nodeDetails(root, id).getValue("node").jsonObject["kernel"] as? JsonObject)?.get("spec") as? JsonObject).orEmpty()
        return buildJsonObject {
            status(root, model).forEach { (key, value) -> put(key, value) }
            put("node", summary(model, node)); put("offset", offset); put("totalFields", fields.size)
            put("callPath", json.encodeToJsonElement(node.declaredAt?.callPath.orEmpty()))
            put("fields", buildJsonArray { fields.entries.drop(offset).take(limit).forEach { (name, _) -> add(buildJsonObject {
                put("name", name); put("value", bounded[name] ?: JsonNull)
                put("matchesDeclaredDefault", name !in nonDefaults)
                put("definedAt", definition?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            }) } })
            put("totalOptions", options.size)
            put("options", buildJsonArray { options.drop(offset).take(limit).forEach { input -> add(buildJsonObject {
                put("build", input.build); json.encodeToJsonElement(input.option).jsonObject.forEach { (key, value) -> put(key, value) }
            }) } })
            put("provenance", "Values are retained spec facts. Default equality does not prove the caller omitted the argument. Option source records the actual resolution origin.")
            put("collections", nodeDetails(root, id).getValue("collections"))
            if (spec == null) put("omission", "This node has no recorded kernel spec")
            if (offset + limit < maxOf(fields.size, options.size)) put("nextOffset", offset + limit)
        }
    }

    fun kernels(root: File, selector: String? = null, offset: Int = 0, limit: Int = 10): JsonObject {
        require(offset >= 0 && limit in 1..20)
        val model = declaredModel(root)
        val groups = selected(model, selector).filter { it.kernel != null }.groupBy { requireNotNull(it.kernel).className }.toSortedMap()
        return buildJsonObject {
            status(root, model).forEach { (key, value) -> put(key, value) }
            put("offset", offset); put("total", groups.size)
            put("kernels", buildJsonArray { groups.entries.drop(offset).take(limit).forEach { (name, nodes) -> add(buildJsonObject {
                put("className", name)
                put("source", nodes.firstNotNullOfOrNull { it.kernel?.source }?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                put("nodeCount", nodes.size); put("nodes", JsonArray(nodes.take(10).map { JsonPrimitive(it.id.toString()) }))
                put("specs", JsonArray(nodes.mapNotNull { it.kernel?.spec }.map { spec ->
                    val fields = json.encodeToJsonElement(KernelSpec.serializer(), spec).jsonObject
                    buildJsonObject { put("type", fields.getValue("type")); put("fields", JsonArray(fields.keys.filter { it != "type" }.map(::JsonPrimitive))) }
                }.distinct()))
                put("tools", json.encodeToJsonElement(nodes.flatMap { it.tools }.distinct()))
                put("missingSpecs", nodes.count { it.kernel?.spec == null })
            }) } })
            if (offset + limit < groups.size) put("nextOffset", offset + limit)
        }
    }

    fun retainedPlan(root: File, id: String): GradlePlan? {
        val node = node(root, id)
        val digest = digest(root.resolve(RelativePath))
        return artifactFiles(root.resolve(GradleProtocol.PlanDirectory)).sortedByDescending(File::lastModified)
            .asSequence().map { json.parseToJsonElement(boundedText(it)).jsonObject }
            .firstOrNull { it["node"] == json.encodeToJsonElement(NodeId.serializer(), node.id) && it["modelDigest"] == JsonPrimitive(digest) }
            ?.let { validatePlan(json.decodeFromJsonElement<GradlePlan>(it)) }
    }

    fun planPage(plan: GradlePlan, offset: Int = 0, limit: Int = 10): JsonObject {
        require(offset >= 0 && limit in 1..20)
        val encoded = json.encodeToJsonElement(GradlePlan.serializer(), plan).jsonObject
        return buildJsonObject {
            encoded.filterKeys { it != "tasks" }.forEach { (key, value) -> put(key, value) }
            put("offset", offset); put("totalTasks", plan.tasks.size)
            put("tasks", buildJsonArray { plan.tasks.drop(offset).take(limit).forEach {
                add(json.encodeToJsonElement(PlannedGradleTask.serializer(), it)) } })
            if (offset + limit < plan.tasks.size) put("nextOffset", offset + limit)
        }
    }

    fun receipts(root: File, id: String): List<Pair<File, RunReceipt>> {
        val model = declaredModel(root)
        val node = model.nodes.single { it.id.toString() == id }
        val taskIds = (node.invocation as? Invocation.Gradle)?.tasks.orEmpty().map { NodeId.task(node.id.build, it) }.toSet()
        return model.builds.flatMap { build -> artifactFiles(root.resolve(build.directory).resolve(GradleProtocol.ReceiptDirectory)) }
            .distinctBy(File::getCanonicalPath).map { it to readReceipt(it) }
            .filter { (_, receipt) -> node.id in receipt.requestedNodes || receipt.tasks.any { it.task in taskIds } }
            .sortedByDescending { it.second.finishedAtEpochMillis }
    }

    fun receiptPage(root: File, id: String, run: String? = null, offset: Int = 0, limit: Int = 10): JsonObject {
        require(offset >= 0 && limit in 1..20)
        val records = receipts(root, id)
        val selected = if (run == null) records.firstOrNull() else records.firstOrNull { it.second.run == run }
        return buildJsonObject {
            put("node", id); put("truth", if (selected == null) "unavailable" else "recorded"); put("totalRuns", records.size)
            put("runs", buildJsonArray { records.take(20).forEach { (file, receipt) -> add(buildJsonObject {
                put("run", receipt.run); put("status", receipt.status.name); put("finishedAtEpochMillis", receipt.finishedAtEpochMillis)
                put("path", file.absolutePath); put("dryRun", receipt.dryRun)
            }) } })
            if (selected == null) { put("omission", if (run == null) "No retained receipt" else "Requested receipt is unavailable"); return@buildJsonObject }
            val receipt = selected.second
            val encoded = json.encodeToJsonElement(RunReceipt.serializer(), receipt).jsonObject
            put("receipt", buildJsonObject {
                encoded.filterKeys { it !in setOf("tasks", "resolvedOptions") }.forEach { (key, value) -> put(key, value) }
                put("offset", offset); put("totalTasks", receipt.tasks.size)
                put("totalOptions", receipt.resolvedOptions.size)
                put("resolvedOptions", json.encodeToJsonElement(receipt.resolvedOptions.drop(offset).take(limit)))
                put("tasks", buildJsonArray { receipt.tasks.drop(offset).take(limit).forEach {
                    add(json.encodeToJsonElement(TaskObservation.serializer(), it)) } })
                if (offset + limit < maxOf(receipt.tasks.size, receipt.resolvedOptions.size)) put("nextOffset", offset + limit)
            })
            put("path", selected.first.absolutePath); put("sha256", digest(selected.first))
            put("matchesCurrentModel", receipt.modelDigest == digest(root.resolve(RelativePath)) && changedDeclarations(root, declaredModel(root)).isEmpty())
        }
    }

    private fun artifactFiles(directory: File): List<File> = directory.listFiles().orEmpty().filter {
        it.isFile && it.extension == "json" && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == directory.canonicalFile
    }.map(File::getCanonicalFile)

    fun encode(model: BuildModel): String = json.encodeToString(model) + "\n"
    fun encode(node: Node): String = json.encodeToString(node)
    fun decodeNode(text: String): Node = json.decodeFromString(text)
    fun readPlan(file: File): GradlePlan = validatePlan(json.decodeFromString<GradlePlan>(boundedText(file)))
    private fun validatePlan(plan: GradlePlan): GradlePlan = plan.also {
        require(it.schema == 1) { "Unsupported Gradle plan schema" }
        require(it.tasks.map { task -> task.id }.distinct().size == it.tasks.size) { "Duplicate planned task" }
        val selected = it.tasks.map { task -> task.id }.toSet()
        require(it.tasks.all { task -> task.dependencies.all(selected::contains) }) { "Unknown planned dependency" }
    }
    fun readReceipt(file: File): RunReceipt = json.decodeFromString<RunReceipt>(boundedText(file)).also {
        require(it.schema == 1) { "Unsupported Gradle receipt schema" }
        require(it.finishedAtEpochMillis >= it.startedAtEpochMillis) { "Invalid receipt duration" }
    }
    fun readPipeline(file: File): DaggerPipelineContract = json.decodeFromString<DaggerPipelineContract>(boundedText(file)).also {
        require(it.schema == 2) { "Unsupported Dagger pipeline schema" }
    }
    fun readRequest(file: File): GradleRequest = json.decodeFromString<GradleRequest>(boundedText(file)).also {
        require(it.schema == 1) { "Unsupported Gradle request schema" }
    }
    fun readRunResult(file: File): GradleRunResult = json.decodeFromString<GradleRunResult>(boundedText(file)).also {
        require(it.schema == 1) { "Unsupported Gradle run-result schema" }
    }
    private fun boundedText(file: File): String = file.inputStream().use {
        val bytes = it.readNBytes(MaxBytes + 1)
        require(bytes.size <= MaxBytes) { "Build artifact exceeds $MaxBytes bytes" }
        bytes.toString(Charsets.UTF_8)
    }
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
