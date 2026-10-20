package dev.shibasis.dependeasy.model

sealed class WorkspaceKernel(val kind: NodeKind) : java.io.Serializable {
    fun plan(spec: KernelSpec.WorkspaceNode): List<NodeId> {
        require(spec.kind == kind) { "${spec.id}: $kind kernel cannot run ${spec.kind}" }
        return (spec.includes + spec.consumes + spec.after).distinct()
    }

    open fun producerGates(spec: KernelSpec.WorkspaceNode): List<NodeId> = emptyList()

    fun validate(spec: KernelSpec.WorkspaceNode) {
        plan(spec)
        spec.requirements.forEach { requirement ->
            check(requirement.option.value == requirement.expected) {
                "${spec.id} requires ${requirement.option.name}=${requirement.expected}; resolved ${requirement.option.value} from ${requirement.option.source}${requirement.option.file?.let { " ($it)" }.orEmpty()}"
            }
        }
    }

    fun complete(spec: KernelSpec.WorkspaceNode): String {
        validate(spec)
        return spec.id.toString() + "\n"
    }
}

object VerificationKernel : WorkspaceKernel(NodeKind.Verification)
object ArtifactSetKernel : WorkspaceKernel(NodeKind.ArtifactSet)
object ExternalKernel : WorkspaceKernel(NodeKind.External)
object ReleaseKernel : WorkspaceKernel(NodeKind.Release) {
    override fun producerGates(spec: KernelSpec.WorkspaceNode): List<NodeId> = spec.after
}
object DeployKernel : WorkspaceKernel(NodeKind.Deploy) {
    override fun producerGates(spec: KernelSpec.WorkspaceNode): List<NodeId> = spec.after
}
