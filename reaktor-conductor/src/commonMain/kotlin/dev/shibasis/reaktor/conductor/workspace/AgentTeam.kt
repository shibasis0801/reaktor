package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TeamLinkKind(val label: String) { Supervises("supervises"), CriticOf("critic of"), PairsWith("pairs with") }

@Serializable
enum class TeamMemory(val label: String) { Elephant("elephant, long memory across tasks"), Goldfish("goldfish, fresh context every task") }

@Serializable
data class TeamRetirement(val by: BoardActor, val at: Long, val reason: String)

@Serializable
data class TeamMember(
    val profile: String,
    val memory: TeamMemory? = null,
    val revision: Long = 0,
    val by: BoardActor? = null,
    val changedAt: Long = 0,
    val retired: TeamRetirement? = null,
)

@Serializable
data class TeamGroup(
    val key: String,
    val title: String,
    val members: List<String>,
    val revision: Long = 0,
    val by: BoardActor? = null,
    val parent: String? = null,
    val summary: String = "",
)

@Serializable
data class TeamLink(val from: String, val to: String, val kind: TeamLinkKind, val revision: Long = 0, val by: BoardActor? = null) {
    fun joins(a: String, b: String, relation: TeamLinkKind): Boolean = kind == relation &&
        (from == a && to == b || relation == TeamLinkKind.PairsWith && from == b && to == a)
}

@Serializable
data class TeamVariant(
    val key: String,
    val topology: String,
    val title: String,
    val prompt: String = "",
    val agents: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val nodePrompts: Map<String, String> = emptyMap(),
    val revision: Long = 0,
    val by: BoardActor? = null,
) {
    fun model(node: String): CatalogPick? = models[node]?.let(ModelCatalog::pick)

    fun occupied(node: String): Boolean = node in agents || node in models

    fun occupant(node: String, profile: (String) -> AgentProfile?): String? =
        agents[node]?.let { id -> profile(id)?.name ?: id } ?: models[node]?.let { key -> model(node)?.label ?: key }

    fun brief(topology: Topology, node: String, profile: (String) -> AgentProfile?): NodeBrief {
        fun who(key: String): String = topology.name(key) + " (${occupant(key, profile) ?: "empty"})"
        fun phrases(edges: List<TopologyEdge>, plain: String, joiner: String, end: (TopologyEdge) -> String): List<String> {
            val (labelled, unlabelled) = edges.partition { it.label.isNotBlank() }
            return listOfNotNull(unlabelled.takeIf { it.isNotEmpty() }?.let { "$plain ${natural(it.map(end).map(::who))}" }) +
                labelled.map { "${it.label} $joiner ${who(end(it))}" }
        }
        val inputs = phrases(topology.into(node), "work from", "from") { it.from }
        val outputs = phrases(topology.outOf(node), "your output to", "to") { it.to }
        val position = buildString {
            append("You are ${topology.name(node)} in “$title”, which follows the ${topology.title} topology (${topology.nodes.size} node${if (topology.nodes.size == 1) "" else "s"}).")
            if (inputs.isNotEmpty()) append(" You receive ${natural(inputs)}.")
            if (outputs.isNotEmpty()) append(" You hand ${natural(outputs)}.")
        }
        return NodeBrief(agents[node]?.let(profile)?.instructions.orEmpty().trim(), nodePrompts[node].orEmpty().trim(), position, prompt.trim())
    }

    private fun natural(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}

@Serializable
data class NodeBrief(val agent: String, val node: String, val position: String, val shared: String) {
    val text: String get() = listOf(agent, node, position, shared).filter(String::isNotBlank).joinToString("\n\n")
}

@Serializable
data class AgentTeam(
    val members: List<TeamMember> = emptyList(),
    val groups: List<TeamGroup> = emptyList(),
    val links: List<TeamLink> = emptyList(),
    val revision: Long = 0,
    val updatedAt: Long = 0,
    val changes: List<PlanChange> = emptyList(),
    val variants: List<TeamVariant> = emptyList(),
    val topologies: List<Topology> = emptyList(),
) {
    fun variant(key: String): TeamVariant? = variants.firstOrNull { it.key == key }

    fun topology(id: String): Topology? = topologies.firstOrNull { it.id == id } ?: Topologies.builtIn(id)

    fun topology(variant: TeamVariant): Topology? = topology(variant.topology)

    val library: List<Topology> get() = Topologies.builtIns + topologies

    fun custom(id: String): Boolean = topologies.any { it.id == id }

    fun variantsOf(topology: String): List<TeamVariant> = variants.filter { it.topology == topology }

    fun attached(profile: String): List<Pair<TeamVariant, String>> = variants.flatMap { variant ->
        variant.agents.filterValues { it == profile }.keys.map { variant to it }
    }

    fun briefs(variant: TeamVariant, profile: (String) -> AgentProfile?): Map<String, NodeBrief> =
        topology(variant)?.let { topology -> topology.nodes.associate { it.key to variant.brief(topology, it.key, profile) } }.orEmpty()

    val active: List<TeamMember> get() = members.filter { it.retired == null }

    val hasHierarchy: Boolean get() = groups.any { it.parent != null }

    fun member(profile: String): TeamMember? = members.firstOrNull { it.profile == profile && it.retired == null }

    fun group(key: String): TeamGroup? = groups.firstOrNull { it.key == key }

    fun groupOf(profile: String): TeamGroup? = groups.firstOrNull { profile in it.members }

    fun childGroups(parentKey: String?): List<TeamGroup> = groups.filter { it.parent == parentKey }

    fun groupPath(key: String): List<TeamGroup> {
        val byKey = groups.associateBy { it.key }
        val visited = HashSet<String>()
        val reversed = ArrayList<TeamGroup>()
        var cursor: TeamGroup? = byKey[key]
        while (cursor != null && visited.add(cursor.key)) {
            reversed += cursor
            cursor = cursor.parent?.let(byKey::get)
        }
        return reversed.asReversed()
    }

    fun groupPathLabel(key: String): String = groupPath(key).joinToString(" › ") { it.title }

    fun allMembersOf(key: String): Set<String> {
        val byParent = groups.groupBy { it.parent }
        val byKey = groups.associateBy { it.key }
        val visited = HashSet<String>()
        val result = LinkedHashSet<String>()
        fun collect(current: String) {
            if (!visited.add(current)) return
            byKey[current]?.members?.let(result::addAll)
            byParent[current].orEmpty().forEach { collect(it.key) }
        }
        collect(key)
        return result
    }

    fun news(since: Long, reader: BoardActor?): List<PlanChange> = changes.filter { it.revision > since && !it.by.same(reader) }
}

@Serializable
sealed interface TeamOp {
    @Serializable @SerialName("add-member")
    data class AddMember(val profile: String, val memory: TeamMemory? = null) : TeamOp

    @Serializable @SerialName("retire-member")
    data class RetireMember(val profile: String, val reason: String) : TeamOp

    @Serializable @SerialName("relate")
    data class Relate(val from: String, val to: String, val kind: TeamLinkKind) : TeamOp

    @Serializable @SerialName("unrelate")
    data class Unrelate(val from: String, val to: String, val kind: TeamLinkKind) : TeamOp

    @Serializable @SerialName("group")
    data class Group(
        val key: String,
        val title: String,
        val members: List<String> = emptyList(),
        val parent: String? = null,
        val summary: String = "",
    ) : TeamOp

    @Serializable @SerialName("ungroup")
    data class Ungroup(val key: String) : TeamOp

    @Serializable @SerialName("topology")
    data class SaveTopology(val topology: Topology) : TeamOp

    @Serializable @SerialName("untopology")
    data class RemoveTopology(val id: String) : TeamOp

    @Serializable @SerialName("variant")
    data class SaveVariant(val key: String, val topology: String, val title: String = "", val prompt: String? = null) : TeamOp

    @Serializable @SerialName("unvariant")
    data class RemoveVariant(val key: String) : TeamOp

    @Serializable @SerialName("attach")
    data class Attach(val variant: String, val node: String, val profile: String? = null, val model: String? = null) : TeamOp

    @Serializable @SerialName("node-prompt")
    data class NodePrompt(val variant: String, val node: String, val prompt: String = "") : TeamOp
}

@Serializable
data class TeamPatch(val ops: List<TeamOp> = emptyList(), val base: Long? = null, val message: String? = null)

@Serializable
data class TeamApplied(val team: AgentTeam, val applied: List<String> = emptyList(), val refused: List<PlanRefusal> = emptyList(), val news: List<PlanChange> = emptyList())

@Serializable
data class TeamRead(val team: AgentTeam, val news: List<PlanChange> = emptyList())

object TeamRules {
    const val MaxMembers = 64
    const val MaxGroups = 32
    const val MaxLinks = 256
    const val MaxVariants = 48
    const val MaxTopologies = 24
    const val MaxNodes = 12
    const val MaxEdges = 48
    const val MaxPrompt = 8_000
    const val MaxNodePrompt = 4_000

    fun topologyProblem(topology: Topology): String? {
        val keys = topology.nodes.map { it.key }
        return when {
            !PlanRules.Key.matches(topology.id) -> "A topology id is 1 to 40 lowercase letters, digits or dashes, starting with a letter"
            Topologies.builtIn(topology.id) != null -> "${topology.id} is a built-in topology; copy it under another id to change it"
            topology.title.isBlank() -> "A topology needs a title"
            topology.nodes.isEmpty() || topology.nodes.size > MaxNodes -> "A topology has 1 to $MaxNodes nodes"
            keys.any { !PlanRules.Key.matches(it) } -> "A node key is 1 to 40 lowercase letters, digits or dashes, starting with a letter"
            keys.toSet().size != keys.size -> "Two nodes share a key"
            topology.nodes.any { it.name.isBlank() || it.name.length > 60 || '\n' in it.name } -> "Every node needs a one-line name of at most 60 characters"
            topology.edges.size > MaxEdges -> "A topology has at most $MaxEdges edges"
            topology.edges.any { it.from !in keys || it.to !in keys || it.from == it.to } -> "An edge joins two different nodes of the topology"
            topology.edges.map { it.from to it.to }.toSet().size != topology.edges.size -> "Two edges join the same nodes in the same direction"
            topology.edges.any { it.label.length > 60 || '\n' in it.label } -> "An edge label is one line of at most 60 characters"
            else -> null
        }
    }

    fun apply(
        team: AgentTeam,
        patch: TeamPatch,
        by: BoardActor,
        now: Long,
        saved: (String) -> Boolean = { true },
        busy: (String) -> String? = { null },
    ): TeamApplied {
        require(patch.ops.size <= PlanRules.MaxOps) { "A patch has at most ${PlanRules.MaxOps} ops; send the rest in another patch" }
        val revision = team.revision + 1
        val members = LinkedHashMap(team.members.associateBy { it.profile })
        val groups = LinkedHashMap(team.groups.associateBy { it.key })
        val links = team.links.toMutableList()
        val variants = LinkedHashMap(team.variants.associateBy { it.key })
        val topologies = LinkedHashMap(team.topologies.associateBy { it.id })
        val refused = mutableListOf<PlanRefusal>()

        fun topology(id: String): Topology? = topologies[id] ?: Topologies.builtIn(id)

        fun node(variant: TeamVariant, key: String): TopologyNode =
            topology(variant.topology)?.node(key) ?: error("${variant.title} has no $key node")
        val summary = mutableListOf<String>()

        fun fresh(changed: Long, changer: BoardActor?, name: String): String? {
            val base = patch.base ?: return null
            return if (changed > base && changer?.same(by) == false)
                "$name changed at revision $changed by ${changer.name} after the revision $base you read; read the team again and decide" else null
        }

        fun active(profile: String): TeamMember = members[profile]?.takeIf { it.retired == null } ?: error("$profile is not on the team")

        fun supervises(from: String, to: String): Boolean {
            val queue = ArrayDeque(listOf(from))
            val seen = HashSet<String>()
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (next == to) return true
                if (seen.add(next)) links.filter { it.kind == TeamLinkKind.Supervises && it.from == next }.forEach { queue.addLast(it.to) }
            }
            return false
        }

        fun pruneEmptyLeafGroups(pendingParentRefs: Set<String> = emptySet()) {
            var removedAny = true
            while (removedAny) {
                removedAny = false
                val activeParents = groups.values.mapNotNullTo(HashSet()) { it.parent } + pendingParentRefs
                val emptyLeaves = groups.values.filter { it.members.isEmpty() && it.key !in activeParents }.map { it.key }
                if (emptyLeaves.isNotEmpty()) {
                    emptyLeaves.forEach(groups::remove)
                    removedAny = true
                }
            }
        }

        patch.ops.forEachIndexed { index, op ->
            runCatching {
                when (op) {
                    is TeamOp.AddMember -> {
                        val current = members[op.profile]?.takeIf { it.retired == null }
                        if (current == null) {
                            require(saved(op.profile)) { "${op.profile} is not a saved profile; save it with profile.save first" }
                            require(members.values.count { it.retired == null } < MaxMembers) { "A team has at most $MaxMembers members" }
                            members[op.profile] = TeamMember(op.profile, op.memory, revision, by, now)
                            "Added ${op.profile}" + (op.memory?.let { " as the ${it.name.lowercase()}" } ?: "")
                        } else if (op.memory == null || op.memory == current.memory) null else {
                            fresh(current.revision, current.by, op.profile)?.let(::error)
                            members[op.profile] = current.copy(memory = op.memory, revision = revision, by = by, changedAt = now)
                            "${op.profile} is now the ${op.memory.name.lowercase()}"
                        }
                    }
                    is TeamOp.RetireMember -> {
                        require(by.kind == BoardActorKind.Person) { "Only a person retires a team member" }
                        require(patch.base == team.revision) { "Read the current team and pass its revision as base before retiring a member" }
                        val member = active(op.profile)
                        val reason = BoardText.bounded(op.reason, "A retirement reason", 1_000)
                        require(reason.isNotEmpty()) { "Give a reason for retiring ${member.profile}" }
                        busy(member.profile)?.let(::error)
                        members[member.profile] = member.copy(retired = TeamRetirement(by, now, reason), revision = revision, by = by, changedAt = now)
                        links.removeAll { it.from == member.profile || it.to == member.profile }
                        groups.values.toList().filter { member.profile in it.members }.forEach { group ->
                            val rest = group.members - member.profile
                            groups[group.key] = group.copy(members = rest, revision = revision, by = by)
                        }
                        val futureParents = patch.ops.drop(index + 1).mapNotNullTo(HashSet()) { (it as? TeamOp.Group)?.parent?.trim()?.takeIf(String::isNotEmpty) }
                        pruneEmptyLeafGroups(futureParents)
                        "Retired ${member.profile}: $reason"
                    }
                    is TeamOp.Relate -> {
                        active(op.from); active(op.to)
                        require(op.from != op.to) { "A member does not relate to itself" }
                        if (links.any { it.joins(op.from, op.to, op.kind) }) null else {
                            require(links.size < MaxLinks) { "A team has at most $MaxLinks links" }
                            require(op.kind != TeamLinkKind.Supervises || !supervises(op.to, op.from)) { "${op.from} supervising ${op.to} would make a supervision cycle" }
                            links += TeamLink(op.from, op.to, op.kind, revision, by)
                            "${op.from} ${op.kind.label} ${op.to}"
                        }
                    }
                    is TeamOp.Unrelate -> {
                        val link = links.firstOrNull { it.joins(op.from, op.to, op.kind) }
                        if (link == null) null else {
                            fresh(link.revision, link.by, "${op.from} ${op.kind.label} ${op.to}")?.let(::error)
                            links.remove(link)
                            "${op.from} no longer ${op.kind.label} ${op.to}"
                        }
                    }
                    is TeamOp.Group -> {
                        require(PlanRules.Key.matches(op.key)) { "A key is 1 to 40 lowercase letters, digits or dashes, starting with a letter" }
                        val title = BoardText.title(op.title)
                        val groupSummary = if (op.summary.isBlank()) "" else BoardText.bounded(op.summary, "group summary", 400)
                        val chosen = op.members.distinct().onEach { active(it) }
                        val current = groups[op.key]
                        val parentKey = op.parent?.trim()?.takeIf { it.isNotEmpty() }
                        if (parentKey != null) {
                            require(parentKey != op.key) { "Group ${op.key} cannot be its own parent" }
                            require(parentKey in groups) { "Parent group $parentKey does not exist; create parent groups first" }
                            val seenAncestors = HashSet<String>()
                            var cursor: String? = parentKey
                            while (cursor != null && seenAncestors.add(cursor)) {
                                require(cursor != op.key) { "Setting $parentKey as parent of ${op.key} would make a group cycle" }
                                cursor = groups[cursor]?.parent
                            }
                        }
                        val futureChildren = patch.ops.drop(index + 1).any { it is TeamOp.Group && it.parent?.trim() == op.key }
                        val existingChildren = groups.values.any { it.parent == op.key }
                        require(chosen.isNotEmpty() || existingChildren || futureChildren) { "A group needs at least one member" }
                        if (current == null) require(groups.size < MaxGroups) { "A team has at most $MaxGroups groups" }
                        else fresh(current.revision, current.by, op.key)?.let(::error)
                        val left = groups.values.filter { it.key != op.key && it.members.any(chosen::contains) }
                        left.forEach { other -> fresh(other.revision, other.by, other.key)?.let(::error) }
                        if (current != null && current.title == title && current.members == chosen && current.parent == parentKey && current.summary == groupSummary) null else {
                            val futureParents = patch.ops.drop(index + 1).mapNotNullTo(HashSet()) { (it as? TeamOp.Group)?.parent?.trim()?.takeIf(String::isNotEmpty) }
                            val moved = left.map { other ->
                                val rest = other.members - chosen.toSet()
                                val hasChildren = groups.values.any { it.parent == other.key } || other.key in futureParents || parentKey == other.key
                                if (rest.isEmpty() && !hasChildren) groups.remove(other.key)
                                else groups[other.key] = other.copy(members = rest, revision = revision, by = by)
                                "${other.members.filter(chosen::contains).joinToString()} left ${other.key}" + if (rest.isEmpty() && !hasChildren) ", which is now gone" else ""
                            }
                            groups[op.key] = TeamGroup(op.key, title, chosen, revision, by, parent = parentKey, summary = groupSummary)
                            pruneEmptyLeafGroups(futureParents + setOfNotNull(if (futureChildren) op.key else null))
                            val targetDesc = if (chosen.isEmpty()) "container" else chosen.joinToString()
                            val parentDesc = if (parentKey != null) " under $parentKey" else ""
                            ((if (current == null) "Grouped " else "Regrouped ") + "$targetDesc as ${op.key} “$title”$parentDesc" +
                                moved.joinToString("") { "; $it" })
                        }
                    }
                    is TeamOp.SaveTopology -> {
                        val shape = op.topology.copy(title = BoardText.title(op.topology.title), summary = op.topology.summary.trim(),
                            nodes = op.topology.nodes.map { it.copy(name = it.name.trim()) }, edges = op.topology.edges.map { it.copy(label = it.label.trim()) })
                        topologyProblem(shape)?.let(::error)
                        val current = topologies[shape.id]
                        if (current == shape) null else {
                            if (current == null) require(topologies.size < MaxTopologies) { "A team keeps at most $MaxTopologies topologies of its own" }
                            topologies[shape.id] = shape
                            val kept = shape.nodes.mapTo(HashSet()) { it.key }
                            val cleared = variants.values.filter { it.topology == shape.id && (it.agents.keys + it.models.keys + it.nodePrompts.keys).any { key -> key !in kept } }
                            cleared.forEach {
                                variants[it.key] = it.copy(agents = it.agents.filterKeys(kept::contains), models = it.models.filterKeys(kept::contains),
                                    nodePrompts = it.nodePrompts.filterKeys(kept::contains), revision = revision, by = by)
                            }
                            (if (current == null) "Added the topology ${shape.id} “${shape.title}”" else "Changed the topology ${shape.id} “${shape.title}”") +
                                (if (cleared.isEmpty()) "" else "; cleared removed nodes in ${cleared.joinToString { it.title }}")
                        }
                    }
                    is TeamOp.RemoveTopology -> {
                        if (topologies[op.id] == null) {
                            require(Topologies.builtIn(op.id) == null) { "${op.id} is a built-in topology and stays" }
                            null
                        } else {
                            val using = variants.values.filter { it.topology == op.id }
                            require(using.isEmpty()) { "Remove its ${using.size} variant${if (using.size == 1) "" else "s"} first: ${using.joinToString { it.title }}" }
                            topologies.remove(op.id)
                            "Removed the topology ${op.id}"
                        }
                    }
                    is TeamOp.SaveVariant -> {
                        require(PlanRules.Key.matches(op.key)) { "A key is 1 to 40 lowercase letters, digits or dashes, starting with a letter" }
                        val shape = topology(op.topology) ?: error("${op.topology} is not a topology; pick one of ${(Topologies.builtIns.map { it.id } + topologies.keys).joinToString()}")
                        val title = BoardText.title(op.title.ifBlank { shape.title })
                        val prompt = op.prompt?.let { BoardText.bounded(it, "A variant prompt", MaxPrompt) }
                        val current = variants[op.key]
                        if (current == null) {
                            require(variants.size < MaxVariants) { "A team has at most $MaxVariants variants" }
                            variants[op.key] = TeamVariant(op.key, shape.id, title, prompt.orEmpty(), revision = revision, by = by)
                            "Added the variant ${op.key} “$title” of ${shape.title}"
                        } else {
                            require(current.topology == shape.id) { "${op.key} is a variant of ${topology(current.topology)?.title ?: current.topology}; remove it before reusing the key" }
                            fresh(current.revision, current.by, op.key)?.let(::error)
                            val renamed = current.title != title
                            val prompted = prompt != null && prompt != current.prompt
                            if (!renamed && !prompted) null else {
                                variants[current.key] = current.copy(title = title, prompt = prompt ?: current.prompt, revision = revision, by = by)
                                listOfNotNull(if (renamed) "Renamed ${op.key} to “$title”" else null,
                                    if (prompted) (if (prompt.isNullOrEmpty()) "Cleared the prompt of “$title”" else "Changed the prompt of “$title”") else null).joinToString("; ")
                            }
                        }
                    }
                    is TeamOp.RemoveVariant -> {
                        val current = variants[op.key]
                        if (current == null) null else {
                            fresh(current.revision, current.by, op.key)?.let(::error)
                            variants.remove(op.key)
                            "Removed the variant “${current.title}”"
                        }
                    }
                    is TeamOp.Attach -> {
                        val current = variants[op.variant] ?: error("${op.variant} is not a variant on this team")
                        val node = node(current, op.node)
                        val profile = op.profile?.trim()?.takeIf(String::isNotEmpty)
                        val model = op.model?.trim()?.takeIf(String::isNotEmpty)
                        require(profile == null || model == null) { "Attach one occupant to a node: a saved agent or a model level" }
                        if (current.agents[node.key] == profile && current.models[node.key] == model) null else {
                            if (profile != null) require(saved(profile)) { "$profile is not a saved agent; save it with profile.save first" }
                            val pick = model?.let { key -> ModelCatalog.pick(key) ?: error("$key is not a model level in the catalog; use a key such as claude-opus-5-5:max") }
                            val agents = if (profile == null) current.agents - node.key else current.agents + (node.key to profile)
                            val models = if (pick == null) current.models - node.key else current.models + (node.key to pick.key)
                            variants[current.key] = current.copy(agents = agents, models = models, revision = revision, by = by)
                            when {
                                profile != null -> "Attached $profile to ${node.name} in “${current.title}”"
                                pick != null -> "Attached ${pick.label} to ${node.name} in “${current.title}”"
                                else -> "Cleared ${node.name} in “${current.title}”"
                            }
                        }
                    }
                    is TeamOp.NodePrompt -> {
                        val current = variants[op.variant] ?: error("${op.variant} is not a variant on this team")
                        val node = node(current, op.node)
                        val prompt = BoardText.bounded(op.prompt, "A node prompt", MaxNodePrompt)
                        if (current.nodePrompts[node.key].orEmpty() == prompt) null else {
                            val prompts = if (prompt.isEmpty()) current.nodePrompts - node.key else current.nodePrompts + (node.key to prompt)
                            variants[current.key] = current.copy(nodePrompts = prompts, revision = revision, by = by)
                            if (prompt.isEmpty()) "Cleared the prompt of ${node.name} in “${current.title}”" else "Changed the prompt of ${node.name} in “${current.title}”"
                        }
                    }
                    is TeamOp.Ungroup -> {
                        val group = groups[op.key]
                        if (group == null) null else {
                            fresh(group.revision, group.by, op.key)?.let(::error)
                            groups.values.filter { it.parent == op.key }.forEach { child ->
                                groups[child.key] = child.copy(parent = group.parent, revision = revision, by = by)
                            }
                            groups.remove(op.key)
                            val futureParents = patch.ops.drop(index + 1).mapNotNullTo(HashSet()) { (it as? TeamOp.Group)?.parent?.trim()?.takeIf(String::isNotEmpty) }
                            pruneEmptyLeafGroups(futureParents)
                            "Ungrouped ${op.key}"
                        }
                    }
                }
            }.onSuccess { line -> line?.let(summary::add) }
                .onFailure { failure -> refused += PlanRefusal(index, op.target(), failure.message ?: "Refused") }
        }
        pruneEmptyLeafGroups()
        patch.message?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotEmpty)?.let { summary += "Says: ${BoardText.bounded(it, "A message", 2_000)}" }
        val news = team.news(patch.base ?: team.revision, by)
        if (summary.isEmpty()) return TeamApplied(team, refused = refused, news = news)
        val changed = team.copy(
            members = members.values.toList(),
            groups = groups.values.toList(),
            links = links.toList(),
            variants = variants.values.toList(),
            topologies = topologies.values.toList(),
            revision = revision,
            updatedAt = now,
            changes = (team.changes + PlanChange(revision, now, by, summary)).takeLast(PlanRules.MaxChanges),
        )
        return TeamApplied(changed, summary, refused, news)
    }

    private fun TeamOp.target(): String? = when (this) {
        is TeamOp.AddMember -> profile
        is TeamOp.RetireMember -> profile
        is TeamOp.Relate -> from
        is TeamOp.Unrelate -> from
        is TeamOp.Group -> key
        is TeamOp.Ungroup -> key
        is TeamOp.SaveTopology -> topology.id
        is TeamOp.RemoveTopology -> id
        is TeamOp.SaveVariant -> key
        is TeamOp.RemoveVariant -> key
        is TeamOp.Attach -> variant
        is TeamOp.NodePrompt -> variant
    }
}
