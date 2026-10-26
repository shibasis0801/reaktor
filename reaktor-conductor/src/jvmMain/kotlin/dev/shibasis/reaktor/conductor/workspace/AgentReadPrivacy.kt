package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.Author

internal fun AgentWorkspace.requirePrivateRead(runId: String) {
    val run = get(runId)
    val request = submission(run.id)
    val independent = when {
        !request.resolvedSeat?.lenses.isNullOrEmpty() -> request.workflow?.stages.orEmpty()
            .filter { it.id.startsWith("lens-") }.mapNotNull { it.agent?.value }.toSet()
        request.collaboration == AgentCollaboration.Compare -> setOfNotNull(request.provider.name.lowercase(), request.partner?.provider?.name?.lowercase())
        else -> emptySet()
    }
    val submitted = checkpoint(run.id)?.completed?.values.orEmpty().mapNotNull { (it.author as? Author.Agent)?.id?.value }.toSet()
    require(submitted.containsAll(independent)) {
        "Blind review: private task, graph, message, memory and session evidence is unavailable until the independent stages submit their own results. Source inspection remains available."
    }
}
