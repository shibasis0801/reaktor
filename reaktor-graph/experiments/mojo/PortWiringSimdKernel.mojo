from collections import InlineArray, List
from testing import assert_equal, assert_false, assert_true

# Reaktor Graph Experiment (Mojo):
# SIMD-Accelerated Port Auto-Wiring, Nearest-Owner Scope Resolution,
# and Two-Phase `launchWhenWired` Barrier Scheduler.
#
# Models the runtime laws tested in:
#   - `src/commonMain/kotlin/dev/shibasis/reaktor/graph/core/node/Wiring.kt`
#   - `src/commonTest/kotlin/dev/shibasis/reaktor/graph/GraphLifecycleIntegrationTest.kt`
#   - `src/commonTest/kotlin/dev/shibasis/reaktor/graph/WiringTest.kt`

alias SIMD_WIDTH: Int = 4
alias MAX_PROVIDERS: Int = 32
alias MAX_SCOPES: Int = 16

alias STATUS_UNCONNECTED: Int32 = 0
alias STATUS_EXPLICIT: Int32 = 1
alias STATUS_AUTO_LOCAL: Int32 = 2
alias STATUS_AUTO_IMPORTED: Int32 = 3
alias STATUS_AUTO_ANCESTOR: Int32 = 4
alias STATUS_AMBIGUOUS: Int32 = -1

alias NODE_BLOCKED_PORT: Int32 = 0
alias NODE_WAITING_GRAPH_WIRE: Int32 = 1
alias NODE_LAUNCHED: Int32 = 2


@value
struct PortKey(Copyable, Movable):
    var type_id: UInt32
    var qualifier_hash: UInt32

    fn matches(read self, other: PortKey) -> Bool:
        return (
            self.type_id == other.type_id
            and self.qualifier_hash == other.qualifier_hash
        )


@value
struct ScopeEntry(Copyable, Movable):
    var scope_id: Int32
    var parent_scope_id: Int32
    var imported_mask: UInt32


@value
struct ProviderEntry(Copyable, Movable):
    var provider_id: Int32
    var owner_scope_id: Int32
    var owner_node_id: Int32
    var key: PortKey
    var ready_epoch: Int32


@value
struct ConsumerEntry(Copyable, Movable):
    var consumer_id: Int32
    var owner_scope_id: Int32
    var owner_node_id: Int32
    var key: PortKey
    var explicit_provider_id: Int32


@value
struct WireDecision(Copyable, Movable):
    var consumer_id: Int32
    var provider_id: Int32
    var status: Int32
    var scope_distance: Int32
    var ready_epoch: Int32


@value
struct WiringBatchReport(Copyable, Movable):
    var total_consumers: Int
    var connected_count: Int
    var unconnected_count: Int
    var ambiguous_count: Int
    var max_ready_epoch: Int32

    fn is_fully_wired(read self) -> Bool:
        return self.unconnected_count == 0 and self.ambiguous_count == 0


struct SoAProviderBank:
    var count: Int
    var provider_ids: InlineArray[Int32, MAX_PROVIDERS]
    var owner_scopes: InlineArray[Int32, MAX_PROVIDERS]
    var owner_nodes: InlineArray[Int32, MAX_PROVIDERS]
    var type_ids: InlineArray[UInt32, MAX_PROVIDERS]
    var qualifier_hashes: InlineArray[UInt32, MAX_PROVIDERS]
    var ready_epochs: InlineArray[Int32, MAX_PROVIDERS]

    fn __init__(out self):
        self.count = 0
        self.provider_ids = InlineArray[Int32, MAX_PROVIDERS](fill=-1)
        self.owner_scopes = InlineArray[Int32, MAX_PROVIDERS](fill=-1)
        self.owner_nodes = InlineArray[Int32, MAX_PROVIDERS](fill=-1)
        self.type_ids = InlineArray[UInt32, MAX_PROVIDERS](fill=0)
        self.qualifier_hashes = InlineArray[UInt32, MAX_PROVIDERS](fill=0)
        self.ready_epochs = InlineArray[Int32, MAX_PROVIDERS](fill=0)

    fn add(mut self, entry: ProviderEntry):
        var idx = self.count
        if idx >= MAX_PROVIDERS:
            return
        self.provider_ids[idx] = entry.provider_id
        self.owner_scopes[idx] = entry.owner_scope_id
        self.owner_nodes[idx] = entry.owner_node_id
        self.type_ids[idx] = entry.key.type_id
        self.qualifier_hashes[idx] = entry.key.qualifier_hash
        self.ready_epochs[idx] = entry.ready_epoch
        self.count = idx + 1

    fn match_chunk_simd(
        read self,
        base_idx: Int,
        target_type: UInt32,
        target_qualifier: UInt32,
    ) -> SIMD[DType.bool, SIMD_WIDTH]:
        var type_vec = SIMD[DType.uint32, SIMD_WIDTH](0)
        var qual_vec = SIMD[DType.uint32, SIMD_WIDTH](0)
        var valid_vec = SIMD[DType.bool, SIMD_WIDTH](False)

        @parameter
        for lane in range(SIMD_WIDTH):
            var slot = base_idx + lane
            if slot < self.count:
                type_vec[lane] = self.type_ids[slot]
                qual_vec[lane] = self.qualifier_hashes[slot]
                valid_vec[lane] = True

        var target_type_vec = SIMD[DType.uint32, SIMD_WIDTH](target_type)
        var target_qual_vec = SIMD[DType.uint32, SIMD_WIDTH](target_qualifier)
        return (
            valid_vec
            & (type_vec == target_type_vec)
            & (qual_vec == target_qual_vec)
        )


struct ScopeHierarchy:
    var count: Int
    var parents: InlineArray[Int32, MAX_SCOPES]
    var imports: InlineArray[UInt32, MAX_SCOPES]

    fn __init__(out self):
        self.count = 0
        self.parents = InlineArray[Int32, MAX_SCOPES](fill=-1)
        self.imports = InlineArray[UInt32, MAX_SCOPES](fill=0)

    fn register_scope(
        mut self,
        scope_id: Int32,
        parent_scope_id: Int32 = -1,
        imported_mask: UInt32 = 0,
    ):
        var idx = Int(scope_id)
        if idx >= 0 and idx < MAX_SCOPES:
            self.parents[idx] = parent_scope_id
            self.imports[idx] = imported_mask
            if idx + 1 > self.count:
                self.count = idx + 1

    fn imports_scope(
        read self, consumer_scope: Int32, provider_scope: Int32
    ) -> Bool:
        if consumer_scope < 0 or Int(consumer_scope) >= MAX_SCOPES:
            return False
        if provider_scope < 0 or Int(provider_scope) >= 32:
            return False
        var mask = self.imports[Int(consumer_scope)]
        var bit = UInt32(1) << UInt32(provider_scope)
        return (mask & bit) != 0

    # Computes resolution tier and hop distance from `consumer_scope` to `provider_scope`:
    #   - 0: same scope (local provider)
    #   - 1: explicitly imported via `consumer_scope.dependsOn(provider_scope)`
    #   - >= 2: ancestor hops (`1 + hops`), or inherited `dependsOn` on an ancestor
    #   - -1: unreachable (sibling/unrelated scope without explicit dependency)
    fn resolution_distance(
        read self, consumer_scope: Int32, provider_scope: Int32
    ) -> Int32:
        if consumer_scope == provider_scope:
            return 0
        if self.imports_scope(consumer_scope, provider_scope):
            return 1

        var cursor = self.parents[Int(consumer_scope)]
        var hops: Int32 = 1
        while cursor >= 0 and hops < Int32(MAX_SCOPES):
            if cursor == provider_scope:
                return hops + 1
            if self.imports_scope(cursor, provider_scope):
                return hops + 1
            cursor = self.parents[Int(cursor)]
            hops += 1

        return -1


fn resolve_consumer_simd(
    consumer: ConsumerEntry,
    read bank: SoAProviderBank,
    read hierarchy: ScopeHierarchy,
) -> WireDecision:
    # Law 1: Explicit connections (`connect(consumer, provider)`) are preserved by `autoWire`.
    if consumer.explicit_provider_id >= 0:
        var explicit_epoch: Int32 = 0
        for i in range(bank.count):
            if bank.provider_ids[i] == consumer.explicit_provider_id:
                explicit_epoch = bank.ready_epochs[i]
                break
        return WireDecision(
            consumer.consumer_id,
            consumer.explicit_provider_id,
            STATUS_EXPLICIT,
            0,
            explicit_epoch,
        )

    var best_provider_id: Int32 = -1
    var best_distance: Int32 = 9999
    var best_epoch: Int32 = 0
    var ambiguous_at_best_distance = False

    var base = 0
    while base < bank.count:
        var matches = bank.match_chunk_simd(
            base, consumer.key.type_id, consumer.key.qualifier_hash
        )

        @parameter
        for lane in range(SIMD_WIDTH):
            if matches[lane]:
                var slot = base + lane
                var prov_scope = bank.owner_scopes[slot]
                var dist = hierarchy.resolution_distance(
                    consumer.owner_scope_id, prov_scope
                )
                if dist >= 0:
                    if dist < best_distance:
                        best_distance = dist
                        best_provider_id = bank.provider_ids[slot]
                        best_epoch = bank.ready_epochs[slot]
                        ambiguous_at_best_distance = False
                    elif (
                        dist == best_distance
                        and bank.provider_ids[slot] != best_provider_id
                    ):
                        ambiguous_at_best_distance = True
        base += SIMD_WIDTH

    if best_provider_id < 0:
        return WireDecision(
            consumer.consumer_id, -1, STATUS_UNCONNECTED, -1, -1
        )

    if ambiguous_at_best_distance:
        return WireDecision(
            consumer.consumer_id, -1, STATUS_AMBIGUOUS, best_distance, -1
        )

    var status = STATUS_AUTO_ANCESTOR
    if best_distance == 0:
        status = STATUS_AUTO_LOCAL
    elif best_distance == 1:
        status = STATUS_AUTO_IMPORTED

    return WireDecision(
        consumer.consumer_id,
        best_provider_id,
        status,
        best_distance,
        best_epoch,
    )


fn auto_wire_batch(
    read consumers: List[ConsumerEntry],
    read bank: SoAProviderBank,
    read hierarchy: ScopeHierarchy,
    mut decisions: List[WireDecision],
) -> WiringBatchReport:
    var connected = 0
    var unconnected = 0
    var ambiguous = 0
    var max_epoch: Int32 = 0

    for i in range(len(consumers)):
        var decision = resolve_consumer_simd(consumers[i], bank, hierarchy)
        decisions.append(decision)
        if decision.status == STATUS_AMBIGUOUS:
            ambiguous += 1
        elif decision.status == STATUS_UNCONNECTED:
            unconnected += 1
        else:
            connected += 1
            if decision.ready_epoch > max_epoch:
                max_epoch = decision.ready_epoch

    return WiringBatchReport(
        len(consumers), connected, unconnected, ambiguous, max_epoch
    )


# Models `Node.launchWhenWired` vs raw `awaitConnected()` from `Wiring.kt`:
# - `early_port_only_epoch`: when the node's own consumer ports are connected
#   (which can still fail if a transitive downstream port is not yet wired!).
# - `safe_launch_when_wired_epoch`: waits for both the node's declared consumer ports
#   AND `graph.awaitWired()` across the whole scope tree (`report.max_ready_epoch`).
@value
struct LaunchTiming(Copyable, Movable):
    var node_id: Int32
    var status: Int32
    var early_port_only_epoch: Int32
    var safe_launch_when_wired_epoch: Int32


fn evaluate_node_launch(
    node_id: Int32,
    read decisions: List[WireDecision],
    read consumers: List[ConsumerEntry],
    report: WiringBatchReport,
) -> LaunchTiming:
    var node_ports_ready_epoch: Int32 = 0
    for i in range(len(consumers)):
        if consumers[i].owner_node_id == node_id:
            var d = decisions[i]
            if d.status <= 0:
                return LaunchTiming(node_id, NODE_BLOCKED_PORT, -1, -1)
            if d.ready_epoch > node_ports_ready_epoch:
                node_ports_ready_epoch = d.ready_epoch

    if not report.is_fully_wired():
        return LaunchTiming(
            node_id,
            NODE_WAITING_GRAPH_WIRE,
            node_ports_ready_epoch,
            -1,
        )

    var safe_epoch = node_ports_ready_epoch
    if report.max_ready_epoch > safe_epoch:
        safe_epoch = report.max_ready_epoch

    return LaunchTiming(
        node_id,
        NODE_LAUNCHED,
        node_ports_ready_epoch,
        safe_epoch,
    )


# -----------------------------------------------------------------------------
# Verification Scenarios (mirroring GraphLifecycleIntegrationTest & WiringTest)
# -----------------------------------------------------------------------------


fn verify_nearest_owner_and_inherited_imports() raises:
    # Scope tree:
    #   0 (root) --dependsOn--> 1 (config)
    #   0 (root) --> 2 (parent) --> 3 (child)
    var hierarchy = ScopeHierarchy()
    hierarchy.register_scope(0, -1, UInt32(1 << 1))
    hierarchy.register_scope(1, 0, 0)
    hierarchy.register_scope(2, 0, 0)
    hierarchy.register_scope(3, 2, 0)

    var text_key = PortKey(1, 0)
    var config_key = PortKey(2, 0)

    var bank = SoAProviderBank()
    bank.add(ProviderEntry(101, 0, 10, text_key, 1))    # root text provider
    bank.add(ProviderEntry(102, 2, 20, text_key, 2))    # parent (nearest) text provider
    bank.add(ProviderEntry(103, 1, 30, config_key, 3))  # config imported on root

    var consumers = List[ConsumerEntry]()
    consumers.append(ConsumerEntry(1, 3, 300, text_key, -1))
    consumers.append(ConsumerEntry(2, 3, 300, config_key, -1))

    var decisions = List[WireDecision]()
    var report = auto_wire_batch(consumers, bank, hierarchy, decisions)

    assert_true(report.is_fully_wired())
    assert_equal(Int(decisions[0].provider_id), 102)
    assert_equal(Int(decisions[0].status), Int(STATUS_AUTO_ANCESTOR))
    assert_equal(Int(decisions[0].scope_distance), 2)
    assert_equal(Int(decisions[1].provider_id), 103)
    assert_equal(Int(decisions[1].status), Int(STATUS_AUTO_ANCESTOR))


fn verify_sibling_isolation_and_explicit_preservation() raises:
    # Scope tree:
    #   0 (parent) --> 1 (providerSibling), 2 (consumerSibling), 3 (importedDep)
    var hierarchy = ScopeHierarchy()
    hierarchy.register_scope(0, -1, 0)
    hierarchy.register_scope(1, 0, 0)
    hierarchy.register_scope(2, 0, UInt32(1 << 3))
    hierarchy.register_scope(3, 0, 0)

    var text_key = PortKey(1, 0)
    var sibling_only_key = PortKey(9, 0)

    var bank = SoAProviderBank()
    bank.add(ProviderEntry(201, 1, 10, sibling_only_key, 1))  # isolated sibling
    bank.add(ProviderEntry(202, 3, 20, text_key, 2))          # imported dependency
    bank.add(ProviderEntry(203, 2, 30, text_key, 1))          # local provider in scope 2

    var consumers = List[ConsumerEntry]()
    # Consumer 1: automatic consumer in scope 2 -> prefers local (203) over imported (202)
    consumers.append(ConsumerEntry(1, 2, 200, text_key, -1))
    # Consumer 2: explicit consumer in scope 2 -> preserves explicit edge to 202
    consumers.append(ConsumerEntry(2, 2, 200, text_key, 202))
    # Consumer 3: requests sibling_only_key in scope 2 -> isolated from sibling scope 1!
    consumers.append(ConsumerEntry(3, 2, 201, sibling_only_key, -1))

    var decisions = List[WireDecision]()
    var report = auto_wire_batch(consumers, bank, hierarchy, decisions)

    assert_false(report.is_fully_wired())
    assert_equal(report.connected_count, 2)
    assert_equal(report.unconnected_count, 1)
    assert_equal(Int(decisions[0].provider_id), 203)
    assert_equal(Int(decisions[0].status), Int(STATUS_AUTO_LOCAL))
    assert_equal(Int(decisions[1].provider_id), 202)
    assert_equal(Int(decisions[1].status), Int(STATUS_AUTO_LOCAL) - 1)  # STATUS_EXPLICIT
    assert_equal(Int(decisions[2].status), Int(STATUS_UNCONNECTED))


fn verify_ambiguity_rejection_and_qualified_ports() raises:
    # Scope 0 imports both Scope 1 and Scope 2 (`autoWire(first, second)`)
    var hierarchy = ScopeHierarchy()
    hierarchy.register_scope(0, -1, UInt32((1 << 1) | (1 << 2)))
    hierarchy.register_scope(1, -1, 0)
    hierarchy.register_scope(2, -1, 0)

    var default_text = PortKey(1, 0)
    var alternate_text = PortKey(1, 777)

    var bank = SoAProviderBank()
    bank.add(ProviderEntry(301, 1, 10, default_text, 1))
    bank.add(ProviderEntry(302, 2, 20, default_text, 1))
    bank.add(ProviderEntry(303, 2, 21, alternate_text, 2))

    var consumers = List[ConsumerEntry]()
    consumers.append(ConsumerEntry(1, 0, 100, default_text, -1))
    consumers.append(ConsumerEntry(2, 0, 100, alternate_text, -1))

    var decisions = List[WireDecision]()
    var report = auto_wire_batch(consumers, bank, hierarchy, decisions)

    assert_equal(report.ambiguous_count, 1)
    assert_equal(Int(decisions[0].status), Int(STATUS_AMBIGUOUS))
    assert_equal(Int(decisions[1].provider_id), 303)
    assert_equal(Int(decisions[1].status), Int(STATUS_AUTO_IMPORTED))


fn verify_launch_when_wired_wavefront() raises:
    # Mirrors `WiringTest.workWaitsForTheWholeTreeNotJustItsOwnPorts`:
    #   Scope 0 (app): Node 10 (Session) consumes Users (ready at epoch 1)
    #   Scope 1 (identity): Node 20 (Users) provides Users (epoch 1), consumes Greeting (ready at epoch 5)
    #   Scope 2 (config): Node 30 provides Greeting (epoch 5)
    # `Session.users.awaitConnected()` resolves at epoch 1 (before `Users.greeting` is wired at epoch 5!),
    # whereas `Session.launchWhenWired(users)` waits until epoch 5 when the whole tree is wired.
    var hierarchy = ScopeHierarchy()
    hierarchy.register_scope(0, -1, UInt32((1 << 1) | (1 << 2)))
    hierarchy.register_scope(1, 0, UInt32(1 << 2))
    hierarchy.register_scope(2, 0, 0)

    var users_key = PortKey(10, 0)
    var greeting_key = PortKey(20, 42)

    var bank = SoAProviderBank()
    bank.add(ProviderEntry(401, 1, 20, users_key, 1))
    bank.add(ProviderEntry(402, 2, 30, greeting_key, 5))

    var consumers = List[ConsumerEntry]()
    consumers.append(ConsumerEntry(1, 0, 10, users_key, -1))
    consumers.append(ConsumerEntry(2, 1, 20, greeting_key, -1))

    var decisions = List[WireDecision]()
    var report = auto_wire_batch(consumers, bank, hierarchy, decisions)

    var session_timing = evaluate_node_launch(10, decisions, consumers, report)
    assert_equal(Int(session_timing.status), Int(NODE_LAUNCHED))
    assert_equal(Int(session_timing.early_port_only_epoch), 1)
    assert_equal(Int(session_timing.safe_launch_when_wired_epoch), 5)


fn main() raises:
    verify_nearest_owner_and_inherited_imports()
    verify_sibling_isolation_and_explicit_preservation()
    verify_ambiguity_rejection_and_qualified_ports()
    verify_launch_when_wired_wavefront()
    print(
        "PortWiringSimdKernel: all 4 Reaktor graph wiring & launchWhenWired"
        " verification suites passed."
    )
