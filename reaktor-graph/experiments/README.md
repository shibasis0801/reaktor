# `reaktor-graph` Formal & High-Performance Kernel Experiments

This directory contains three self-contained experiments inside `reaktor-graph`—one in **TLA+**, one in **Bend 2**, and one in **Mojo**—each modeling and verifying a core runtime invariant or execution kernel of `reaktor-graph`.

| Language | Experiment Path | `reaktor-graph` Surface Modeled | Core Invariants / Laws |
|---|---|---|---|
| **TLA+** | `tla/SessionSlotLifecycle.tla` (+ 4 `.cfg` models) | `ui/SessionSlot.kt` & `SessionSlotTest.kt` | Off-main activation build, `collectLatest` cancellation, Compose `DisposableEffect` mount reference counting (`shown`/`retired`), and leak-free `finally` disposal |
| **Bend 2** | `bend2/ScopeResolutionFold.bend` | `GraphLifecycleIntegrationTest.kt` (`autoWire` & containment tree) | Parallel top-down inherited environment + bottom-up `WiringReport` synthesis with inductive proofs of nearest-owner port shadowing (`lookup_local_shadow`) |
| **Mojo** | `mojo/PortWiringSimdKernel.mojo` | `core/node/Wiring.kt` (`awaitConnected`, `launchWhenWired`) & `autoWire` | SoA SIMD port-signature matching (`SIMD[DType.uint32, 4]`), local/imported/ancestor precedence, sibling isolation, ambiguity rejection, and two-phase `launchWhenWired` barrier scheduling |

---

## 1. TLA+ Experiment: `tla/SessionSlotLifecycle.tla`

### What it models
`SessionSlot<K>` (`src/commonMain/kotlin/dev/shibasis/reaktor/graph/ui/SessionSlot.kt`) manages session-scoped child `Graph` activations across concurrent identity changes (`StateFlow<K?>`), background graph construction (`Dispatchers.Default`), and Compose UI composition lifecycles (`DisposableEffect` mount/unmount reference counts).

The specification models:
- Concurrent identity emissions (`EmitIdentity`, `EmitSignOut`), background build completion/failure/cancellation (`PublishBuild`, `FailBuild`, `DiscardBuild`), Compose UI mounts/unmounts (`MountShown`, `UnmountShown`), and container teardown (`CloseSlot`).
- Six safety invariants combined under `Safety`:
  - `NeverCloseShownGraph`: A mounted graph (`shown[g] > 0`) is never closed while still composed on screen; it enters `retired` until the last `DisposableEffect.onDispose` unmounts it.
  - `ActiveGraphOpen`: The active `current` graph is always constructed, open, and not retired.
  - `RetiredGraphsAwaitUnmount`: Every graph in `retired` has `shown[g] > 0` and has not yet been closed.
  - `NoActivationLeak`: Every constructed graph is accounted for (in-flight in `building`, active in `current`, awaiting unmount in `retired`, or disposed in `closed`).
  - `CurrentMatchesIdentity`: Once in-flight builds settle without failure, `activeIdentity` and `activeEpoch` match the latest `targetIdentity` and `epoch`.
  - `AllClosedWhenQuiescent`: When the slot closes and all builds and UI mounts drain (`Quiescent`), `closed = constructed` and `retired = {}`.
- Three mutation configs (`SessionSlotLifecycle_Unsafe*.cfg`) that flip one design guard at a time (`DeferCloseWhileShown = FALSE`, `CloseDiscardedInFinally = FALSE`, `CheckEpochOnCommit = FALSE`) and produce minimal counterexample traces.

### How to run
Requires SANY and TLC (`tlc` / `tla2tools.jar`, installed at `/Users/ovd/.local/bin/sany` and `/Users/ovd/.local/bin/tlc`).

```bash
cd /Users/ovd/dev/reaktor/reaktor-graph/experiments/tla

# 1. Syntax & semantic check
sany SessionSlotLifecycle.tla

# 2. Verify the safe production design (passes all 6 invariants across 1,046 distinct states, depth 14)
tlc -metadir /tmp/tlc-safe -cleanup -workers 1 -config SessionSlotLifecycle.cfg SessionSlotLifecycle.tla

# 3. Run the three bug-injection models to see minimal counterexample traces:
#    - Closing a replaced graph while Compose still has it mounted (violates NeverCloseShownGraph in 5 states)
tlc -metadir /tmp/tlc-unsafe1 -cleanup -workers 1 -config SessionSlotLifecycle_UnsafeCloseShown.cfg SessionSlotLifecycle.tla

#    - Omitting `finally { built?.close() }` on failed/cancelled builds (violates NoActivationLeak in 3 states)
tlc -metadir /tmp/tlc-unsafe2 -cleanup -workers 1 -config SessionSlotLifecycle_UnsafeDropFinally.cfg SessionSlotLifecycle.tla

#    - Allowing a superseded build to publish after a newer identity/sign-out (violates CurrentMatchesIdentity in 4 states)
tlc -metadir /tmp/tlc-unsafe3 -cleanup -workers 1 -config SessionSlotLifecycle_UnsafeLatePublish.cfg SessionSlotLifecycle.tla
```

---

## 2. Bend 2 Experiment: `bend2/ScopeResolutionFold.bend`

### What it models
Models `reaktor-graph`'s hierarchical scope containment tree (`ScopeTree`) and nearest-owner port auto-wiring (`evaluate_graph`) as an interaction-net fold with formal inductive proofs checked by Bend 2:
- **Top-down environment inheritance (`env_extend`)**: Child scopes prepend their local `ProviderPort` table ahead of inherited ancestor providers.
- **Bottom-up wiring synthesis (`evaluate_graph` / `merge_reports`)**: Evaluates left and right child scope subtrees in parallel and folds `(scopeCount, wiredCount, missingCount, worstHopLatency)`.
- **Formal inductive proofs**:
  - `law env_extend_nil_left`: Extending an inherited environment with `EnvNil{}` is the identity.
  - `law nat_eq_refl`: Port key equality `nat_eq(k, k)` is reflexive (`True{}`).
  - `law lookup_local_shadow`: Nearest-owner shadowing (`env_lookup(k, EnvCons{k, pid, cost, rest}) == Resolved{pid, cost}`).
  - `law zero_missing_right_id`: `Nat.add(m, 0n) == m`.
- **7-scope Reaktor topology (`sample_reaktor_topology`)**: Evaluates `app (100)` -> `identityBranch (200)` (`oauthLeaf (210)`, `profileLeaf (220)`) and `sessionBranch (300)` (`messagingLeaf (310)`, `moderationLeaf (320)`), yielding `Report{scopeCount: 7, wiredCount: 12, missingCount: 2, worstHopLatency: 14}` and checksum `8234`.

### How to run
Requires the Bend 2 CLI (`bend` / `bend2`):

```bash
cd /Users/ovd/dev/reaktor/reaktor-graph/experiments/bend2

# Type-check and verify all formal laws (`env_extend_nil_left`, `nat_eq_refl`, `lookup_local_shadow`, `zero_missing_right_id`)
bend check ScopeResolutionFold.bend

# Run the parallel containment-tree fold (expected output: 8234)
bend run ScopeResolutionFold.bend
```

---

## 3. Mojo Experiment: `mojo/PortWiringSimdKernel.mojo`

### What it models
Implements a cache-aligned Structure-of-Arrays (`SoAProviderBank`) and `SIMD[DType.uint32, 4]` port-matching kernel (`PortWiringSimdKernel.mojo`) covering the runtime wiring laws in `GraphLifecycleIntegrationTest.kt` and `Wiring.kt`:
- **SIMD Port-Key Matching (`match_chunk_simd`)**: Compares 4 provider `(type_id, qualifier_hash)` pairs per cycle against a consumer's `PortKey`.
- **Reaktor Scope Precedence (`resolution_distance` & `resolve_consumer_simd`)**:
  1. Preserves existing explicit edges (`connect(consumer, provider)` -> `STATUS_EXPLICIT`).
  2. Prefers local scope providers (`distance = 0` -> `STATUS_AUTO_LOCAL`).
  3. Checks explicitly imported sibling scopes (`dependsOn` bitmask, `distance = 1` -> `STATUS_AUTO_IMPORTED`), flagging `STATUS_AMBIGUOUS` when two imported dependencies provide the same `(type_id, qualifier_hash)`.
  4. Walks the parent scope chain (`distance = 1 + hops` -> `STATUS_AUTO_ANCESTOR`), including `dependsOn` imports inherited from ancestor scopes.
  5. Isolates unimported sibling scopes (`distance = -1` -> `STATUS_UNCONNECTED`).
- **Two-Phase `launchWhenWired` Wavefront Scheduler (`evaluate_node_launch`)**: Contrasts raw `consumer.awaitConnected()` readiness (`early_port_only_epoch`) against `Node.launchWhenWired(ports...)` (`safe_launch_when_wired_epoch`), which waits for both the node's own ports and the full graph wiring barrier (`graph.awaitWired()`).

### How to run
Requires the Mojo toolchain (`mojo` via Modular / `pixi`):

```bash
cd /Users/ovd/dev/reaktor/reaktor-graph/experiments/mojo

# Execute the self-checking verification suite
mojo run PortWiringSimdKernel.mojo
```
