package dev.shibasis.reaktor.performance

internal fun ReaktorPerformanceScope.measureAttributes(): Map<String, String> {
    val reserved = listOfNotNull(
        graphId?.let { "reaktor_graph_id" to it }, nodeId?.let { "reaktor_node_id" to it },
        route?.let { "reaktor_route" to it }, service?.let { "reaktor_service" to it },
        operation?.let { "reaktor_operation" to it }, module?.let { "reaktor_module" to it },
    ).toMap().mapValues { it.value.take(256) }
    val custom = attributes.filterKeys { key -> key.length in 1..256 && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' } }
        .filterKeys { it !in reserved }.entries.take(100 - reserved.size).associate { it.key to it.value.take(256) }
    return reserved + custom
}
