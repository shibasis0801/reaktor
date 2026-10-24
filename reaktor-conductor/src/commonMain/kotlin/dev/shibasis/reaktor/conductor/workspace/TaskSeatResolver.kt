package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.Serializable

@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    val runtime: RuntimeKind,
    val model: String? = null,
    val effort: String? = null,
    val instructions: String = "",
    val access: TaskAccess = TaskAccess.Read,
    val tools: ToolPolicy = ToolPolicy(),
    val revision: Long = 0,
    val by: BoardActor? = null,
    val updatedAt: Long = 0,
    val builtIn: Boolean = false,
)

@Serializable
data class ResolvedTaskSeat(
    val seat: TaskSeat,
    val profileRevision: Long? = null,
    val instructions: String = "",
    val tools: ToolPolicy = ToolPolicy(),
    val notes: List<String> = emptyList(),
    val name: String? = null,
)

class TaskSeatResolver(
    private val profile: (String) -> AgentProfile = { error("No agent profile named $it") },
    private val capability: (RuntimeKind) -> ProviderCapability? = { null },
) {
    fun resolve(request: TaskSeat): ResolvedTaskSeat {
        TaskGraphRules.validSeat(request)
        if (request.lead) return ResolvedTaskSeat(request.copy(access = request.access ?: TaskAccess.Read))
        val profile = request.profile?.let(profile)
        val runtime = requireNotNull(request.agent ?: profile?.runtime) { "Choose an agent or a profile" }
        require(profile == null || runtime == profile.runtime || profile.model == null || request.model != null) {
            "Profile ${profile?.id} uses ${profile?.runtime} model ${profile?.model}; name a model override when choosing $runtime"
        }
        val capability = capability(runtime)
        val model = request.model ?: profile?.model ?: capability?.defaultModel
        val effort = request.effort ?: profile?.effort
        val support = capability?.model(model)?.effort ?: capability?.effort
        val resolution = support.resolve(effort?.let(::NativeEffort))
        if (resolution is EffortResolution.Unsupported) throw UnsupportedEffortException(resolution)
        val access = request.access ?: profile?.access ?: TaskAccess.Read
        return ResolvedTaskSeat(
            request.copy(agent = runtime, model = model, effort = effort, access = access),
            profileRevision = profile?.revision,
            name = profile?.name,
            instructions = profile?.instructions.orEmpty(),
            tools = (profile?.tools ?: ToolPolicy()).copy(allowWrites = access == TaskAccess.Write),
            notes = buildList {
                profile?.let { add("Profile ${it.id} revision ${it.revision}") }
                if (request.agent != null && profile != null) add("Task runtime overrides profile runtime: $runtime")
                if (request.model != null && profile != null) add("Task model overrides profile model: $model")
                if (request.effort != null && profile != null) add("Task effort overrides profile effort: $effort")
                if (request.access != null && profile != null) add("Task access overrides profile access: $access")
                if (request.model == null && profile?.model == null && model != null) add("Model uses the configured default: $model")
                if (effort == null) add("No effort requested; the provider default applies")
                else if (support?.supported == null) add("The probe does not enumerate efforts; $effort is retained for provider validation")
                if (capability == null) add("No capability probe is available for $runtime; execution requires a configured runtime")
            },
        )
    }
}
