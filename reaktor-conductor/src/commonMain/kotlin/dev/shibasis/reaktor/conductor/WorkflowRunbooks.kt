package dev.shibasis.reaktor.conductor

/** These are editable authoring templates, never fabricated execution results. */
object WorkflowRunbooks {
    fun reviewRepair(builder: RuntimeKind, reviewer: RuntimeKind, checkId: String? = null, subjects: List<String> = emptyList()): WorkflowDefinition {
        val build = AgentSpec(AgentId("builder"), "Builder", builder, "Implement the task in the selected graph and repository. Follow repository instructions.", tools = ToolPolicy(allowWrites = true))
        val review = AgentSpec(AgentId("reviewer"), "Reviewer", reviewer, "Review the exact changes and evidence. Report concrete defects. Request repair when evidence is missing.")
        val stages = mutableListOf(WorkflowStage("build", "Build", build.id, "Implement the requested behavior and report graph subjects and changed files."))
        val edges = mutableListOf<WorkflowEdge>()
        if (checkId != null) {
            stages += WorkflowStage("check", "Verify build", action = WorkflowAction.Check, checkId = checkId)
            edges += WorkflowEdge("build", "check")
        }
        stages += WorkflowStage("review", "Independent review", review.id, "Inspect source and upstream check receipts. Return the Decision contract.", contract = WorkflowContract.Decision)
        edges += WorkflowEdge(if (checkId == null) "build" else "check", "review")
        stages += WorkflowStage("repair", "Repair once", build.id, "Fix only the review findings. Preserve unrelated changes.")
        edges += WorkflowEdge("review", "repair", WorkflowCondition.Repair)
        if (checkId != null) edges += WorkflowEdge("check", "repair", WorkflowCondition.Failed)
        if (checkId != null) {
            stages += WorkflowStage("recheck", "Verify repair", action = WorkflowAction.Check, checkId = checkId)
            edges += WorkflowEdge("repair", "recheck")
        }
        stages += WorkflowStage("rereview", "Review repair", review.id, "Review the repair and its evidence. Pass only if it satisfies the original task. Fail if it still needs work; this runbook allows one repair.", contract = WorkflowContract.Decision)
        edges += WorkflowEdge(if (checkId == null) "repair" else "recheck", "rereview")
        stages += WorkflowStage("accept", "Review before accepting", instruction = "Inspect the source diff and kernel evidence. Continuing records this workflow gate; candidate acceptance and applying worktrees remain explicit actions.", action = WorkflowAction.Gate)
        edges += WorkflowEdge("review", "accept", WorkflowCondition.Pass)
        edges += WorkflowEdge("rereview", "accept", WorkflowCondition.Pass)
        return WorkflowDefinition("build-review-repair", "Build → review → repair", listOf(build, review), stages, edges, subjects)
    }
}
