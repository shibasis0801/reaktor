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
data class TeamStructure(
    val key: String,
    val template: String,
    val title: String,
    val seats: Map<String, String> = emptyMap(),
    val revision: Long = 0,
    val by: BoardActor? = null,
) {
    val shape: StructureTemplate? get() = AgentStructures.template(template)
}

@Serializable
data class AgentTeam(
    val members: List<TeamMember> = emptyList(),
    val groups: List<TeamGroup> = emptyList(),
    val links: List<TeamLink> = emptyList(),
    val revision: Long = 0,
    val updatedAt: Long = 0,
    val changes: List<PlanChange> = emptyList(),
    val structures: List<TeamStructure> = emptyList(),
) {
    fun structure(key: String): TeamStructure? = structures.firstOrNull { it.key == key }

    fun seated(profile: String): List<Pair<TeamStructure, String>> = structures.flatMap { structure ->
        structure.seats.filterValues { it == profile }.keys.map { structure to it }
    }

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

    @Serializable @SerialName("structure")
    data class Structure(val key: String, val template: String, val title: String = "") : TeamOp

    @Serializable @SerialName("seat")
    data class Seat(val structure: String, val seat: String, val profile: String? = null) : TeamOp

    @Serializable @SerialName("unstructure")
    data class Unstructure(val key: String) : TeamOp
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
    const val MaxStructures = 24

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
        val structures = LinkedHashMap(team.structures.associateBy { it.key })
        val refused = mutableListOf<PlanRefusal>()
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
                    is TeamOp.Structure -> {
                        require(PlanRules.Key.matches(op.key)) { "A key is 1 to 40 lowercase letters, digits or dashes, starting with a letter" }
                        val template = AgentStructures.template(op.template) ?: error("${op.template} is not a structure; pick one of ${AgentStructures.templates.joinToString { it.id }}")
                        val title = BoardText.title(op.title.ifBlank { template.title })
                        val current = structures[op.key]
                        if (current == null) {
                            require(structures.size < MaxStructures) { "A team has at most $MaxStructures structures" }
                            structures[op.key] = TeamStructure(op.key, template.id, title, revision = revision, by = by)
                            "Added the ${template.title} structure as ${op.key} “$title”"
                        } else {
                            require(current.template == template.id) { "${op.key} is a ${current.shape?.title ?: current.template}; remove it before reusing the key" }
                            fresh(current.revision, current.by, op.key)?.let(::error)
                            if (current.title == title) null else {
                                structures[op.key] = current.copy(title = title, revision = revision, by = by)
                                "Renamed ${op.key} to “$title”"
                            }
                        }
                    }
                    is TeamOp.Seat -> {
                        val current = structures[op.structure] ?: error("${op.structure} is not a structure on this team")
                        val seat = current.shape?.seat(op.seat) ?: error("${current.title} has no ${op.seat} seat")
                        val profile = op.profile?.trim()?.takeIf(String::isNotEmpty)
                        if (current.seats[seat.key] == profile) null else {
                            if (profile != null) require(saved(profile)) { "$profile is not a saved profile; save it with profile.save first" }
                            val seats = if (profile == null) current.seats - seat.key else current.seats + (seat.key to profile)
                            structures[current.key] = current.copy(seats = seats, revision = revision, by = by)
                            if (profile == null) "Emptied the ${seat.role} seat in ${current.title}" else "Seated $profile as ${seat.role} in ${current.title}"
                        }
                    }
                    is TeamOp.Unstructure -> {
                        val current = structures[op.key]
                        if (current == null) null else {
                            fresh(current.revision, current.by, op.key)?.let(::error)
                            structures.remove(op.key)
                            "Removed the ${current.title} structure"
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
            structures = structures.values.toList(),
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
        is TeamOp.Structure -> key
        is TeamOp.Seat -> structure
        is TeamOp.Unstructure -> key
    }
}
