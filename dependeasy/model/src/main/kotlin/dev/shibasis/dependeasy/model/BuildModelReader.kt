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
        return model
    }

    fun read(root: File): BuildModel {
        val model = declaredModel(root)
        val stale = changedDeclarations(root, model)
        require(stale.isEmpty()) { "Build model is stale: ${stale.size} declaration inputs changed; rerun exportBuildModel" }
        return model
    }

    private fun declaredModel(root: File): BuildModel {
        val file = root.resolve(RelativePath)
        require(file.isFile) { "Run ./gradlew exportBuildModel in this workspace" }
        val bytes = file.inputStream().use { it.readNBytes(MaxBytes + 1) }
        require(bytes.size <= MaxBytes) { "Build model exceeds $MaxBytes bytes" }
        val model = decode(bytes.toString(Charsets.UTF_8))
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile }
        model.declarations.forEach { input ->
            val owner = directories.getValue(input.build)
            require(owner.resolve(input.path).canonicalFile.toPath().startsWith(owner.toPath())) { "Declaration escapes its build" }
        }
        return model
    }

    private fun changedDeclarations(root: File, model: BuildModel): List<DeclarationInput> {
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile }
        return model.declarations.filter { input ->
            val owner = directories.getValue(input.build)
            val declaration = owner.resolve(input.path).canonicalFile
            require(declaration.toPath().startsWith(owner.toPath())) { "Declaration escapes its build" }
            when (input.kind) {
                DeclarationInputKind.File -> !declaration.isFile || digest(declaration) != input.sha256
                DeclarationInputKind.Membership -> BuildDeclarationInputs.membership(declaration) != input.sha256
            }
        }
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
            put("node", json.encodeToJsonElement(Node.serializer(), selected))
            put("effectiveHost", json.encodeToJsonElement(HostRequirement.serializer(), model.requiredHost(selected.id)))
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
            put("producer", json.encodeToJsonElement(Producer.serializer(), model.producer))
            put("builds", buildJsonArray { model.builds.forEach { add(json.encodeToJsonElement(BuildIdentity.serializer(), it)) } })
            put("view", view); put("offset", offset); put("total", values.size)
            put(view, JsonArray(values.drop(offset).take(limit)))
            if (offset + limit < values.size) put("nextOffset", offset + limit)
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
                encoded.filterKeys { it != "tasks" }.forEach { (key, value) -> put(key, value) }
                put("offset", offset); put("totalTasks", receipt.tasks.size)
                put("tasks", buildJsonArray { receipt.tasks.drop(offset).take(limit).forEach {
                    add(json.encodeToJsonElement(TaskObservation.serializer(), it)) } })
                if (offset + limit < receipt.tasks.size) put("nextOffset", offset + limit)
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
        require(it.schema == 1) { "Unsupported Dagger pipeline schema" }
        require(it.targets.map { node -> node.id }.distinct().size == it.targets.size) { "Duplicate pipeline target" }
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
