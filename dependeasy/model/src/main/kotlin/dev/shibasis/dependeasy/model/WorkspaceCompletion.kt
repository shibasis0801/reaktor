package dev.shibasis.dependeasy.model

object WorkspaceCompletion {
    fun plan(spec: KernelSpec.WorkspaceNode): List<NodeId> =
        (spec.includes + spec.consumes + spec.after).distinct()

    fun complete(spec: KernelSpec.WorkspaceNode): String {
        spec.requirements.forEach { requirement ->
            check(requirement.option.value == requirement.expected) {
                "${spec.id} requires ${requirement.option.name}=${requirement.expected}; resolved ${requirement.option.value} from ${requirement.option.source}${requirement.option.file?.let { " ($it)" }.orEmpty()}"
            }
        }
        return spec.id.toString() + "\n"
    }
}
