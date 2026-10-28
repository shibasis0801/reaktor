import Base

# Reaktor Graph Experiment (Bend 2):
# Parallel Containment-Tree Attribute Fold & Nearest-Owner Port Resolution
# with Formal Inductive Proofs of Scope Shadowing Laws.

type Env is Data:
  EnvNil
  EnvCons{key: Nat, providerId: U32, hopCost: U32, tail: Env}

type ResolveResult is Data:
  Resolved{providerId: U32, hopCost: U32}
  Unresolved{missingKey: Nat}

type ConsumerSpec is Data:
  ConsumerNil
  ConsumerCons{key: Nat, tail: ConsumerSpec}

type ScopeTree is Data:
  ScopeLeaf{scopeId: U32, localProviders: Env, consumers: ConsumerSpec}
  ScopeBranch{
    scopeId: U32,
    localProviders: Env,
    consumers: ConsumerSpec,
    left: ScopeTree,
    right: ScopeTree,
  }

type WiringReport is Data:
  Report{
    scopeCount: U32,
    wiredCount: U32,
    missingCount: U32,
    worstHopLatency: U32,
  }

def nat_eq(a: Nat, b: Nat) -> Bool:
  match a b:
    case 0n 0n:
      True{}
    case 0n (1n+bp):
      False{}
    case (1n+ap) 0n:
      False{}
    case (1n+ap) (1n+bp):
      nat_eq(ap, bp)

def u32_max(+a: U32, +b: U32) -> U32:
  if (a >= b : Bool):
    a
  else:
    b

def env_extend(local: Env, inherited: Env) -> Env:
  match local:
    case EnvNil{}:
      inherited
    case EnvCons{key, providerId, hopCost, tail}:
      EnvCons{key, providerId, hopCost, env_extend(tail, inherited)}

def env_lookup_branch(matched: Bool, +key: Nat, providerId: U32, hopCost: U32, tail: Env) -> ResolveResult:
  match matched:
    case True{}:
      Resolved{providerId, hopCost}
    case False{}:
      env_lookup(key, tail)

def env_lookup(+key: Nat, env: Env) -> ResolveResult:
  match env:
    case EnvNil{}:
      Unresolved{key}
    case EnvCons{+entryKey, providerId, hopCost, tail}:
      env_lookup_branch(nat_eq(key, entryKey), key, providerId, hopCost, tail)

def accumulate_consumer(res: ResolveResult, rest: WiringReport) -> WiringReport:
  match res:
    case Resolved{providerId, +hopCost}:
      match rest:
        case Report{scopes, wired, missing, +worst}:
          Report{
            scopes,
            (wired + 1 : U32),
            missing,
            u32_max(hopCost, worst),
          }
    case Unresolved{missingKey}:
      match rest:
        case Report{scopes, wired, missing, worst}:
          Report{
            scopes,
            wired,
            (missing + 1 : U32),
            worst,
          }

def wire_consumers(consumers: ConsumerSpec, +env: Env) -> WiringReport:
  match consumers:
    case ConsumerNil{}:
      Report{1, 0, 0, 0}
    case ConsumerCons{+key, tail}:
      resolved = env_lookup(key, env)
      rest = wire_consumers(tail, env)
      accumulate_consumer(resolved, rest)

def merge_reports(own: WiringReport, left: WiringReport, right: WiringReport) -> WiringReport:
  match own:
    case Report{s0, w0, m0, +l0}:
      match left:
        case Report{s1, w1, m1, +l1}:
          match right:
            case Report{s2, w2, m2, +l2}:
              childWorst = u32_max(l1, l2)
              Report{
                (s0 + s1 + s2 : U32),
                (w0 + w1 + w2 : U32),
                (m0 + m1 + m2 : U32),
                (l0 + childWorst : U32),
              }

# Parallel top-down inherited environment + bottom-up synthesized report fold.
def evaluate_graph(tree: ScopeTree, +inherited: Env) -> WiringReport:
  match tree:
    case ScopeLeaf{scopeId, localProviders, consumers}:
      effectiveEnv = env_extend(localProviders, inherited)
      wire_consumers(consumers, effectiveEnv)
    case ScopeBranch{scopeId, localProviders, consumers, left, right}:
      effectiveEnv = env_extend(localProviders, inherited)
      ownReport = wire_consumers(consumers, effectiveEnv)
      leftReport = evaluate_graph(left, effectiveEnv)
      rightReport = evaluate_graph(right, effectiveEnv)
      merge_reports(ownReport, leftReport, rightReport)

# -----------------------------------------------------------------------------
# Formal Laws & Proofs (verified by Bend 2's proof checker)
# -----------------------------------------------------------------------------

# Law 1: Extending an inherited environment with an empty local provider table
# returns the inherited environment unchanged.
law env_extend_nil_left:
  for env: Env { env_extend(EnvNil{}, env) == env : Env }

def env_extend_nil_left(env):
  {==}

# Law 2: Port key equality over Nat is reflexive.
law nat_eq_refl:
  for k: Nat { nat_eq(k, k) == True{} : Bool }

def nat_eq_refl(k):
  match k:
    case 0n:
      {==}
    case 1n+kp:
      %nat_eq_refl(kp) : {nat_eq(kp, kp) == _ : Bool}
      {==}

# Law 3 (Nearest-Owner Shadowing): Looking up key `k` in a scope that locally
# provides `k` always resolves to the local provider `(pid, cost)`, shadowing
# any ancestor provider in `rest`.
law lookup_local_shadow:
  for k: Nat, pid: U32, cost: U32, rest: Env {
    env_lookup(k, EnvCons{k, pid, cost, rest}) == Resolved{pid, cost} : ResolveResult
  }

def lookup_local_shadow(k, pid, cost, rest):
  %nat_eq_refl(k) : {env_lookup_branch(_, k, pid, cost, rest) == Resolved{pid, cost} : ResolveResult}
  {==}

# Law 4: A fully wired child scope (0n missing ports) is the right identity of
# missing-port count accumulation.
law zero_missing_right_id:
  for m: Nat { Nat.add(m, 0n) == m : Nat }

def zero_missing_right_id(m):
  match m:
    case 0n:
      {==}
    case 1n+mp:
      %zero_missing_right_id(mp) : {1n+Nat.add(mp, 0n) == 1n+_ : Nat}
      {==}

# -----------------------------------------------------------------------------
# Sample Reaktor Graph Topology (7 Scopes: app -> identity/session -> features)
# Port keys:
#   1n = ConfigPort, 2n = TelemetryPort, 3n = AuthTokenPort,
#   4n = SessionStorePort, 5n = ChatTransportPort, 9n = UnwiredExperimentalPort
# -----------------------------------------------------------------------------

def sample_reaktor_topology() -> ScopeTree:
  rootProviders = EnvCons{1n, 101, 2, EnvCons{2n, 102, 3, EnvNil{}}}
  rootConsumers = ConsumerCons{1n, ConsumerNil{}}

  oauthLeaf = ScopeLeaf{
    210,
    EnvNil{},
    ConsumerCons{1n, ConsumerCons{3n, ConsumerNil{}}},
  }
  profileLeaf = ScopeLeaf{
    220,
    EnvCons{2n, 221, 1, EnvNil{}},
    ConsumerCons{2n, ConsumerCons{3n, ConsumerNil{}}},
  }
  identityBranch = ScopeBranch{
    200,
    EnvCons{3n, 201, 5, EnvNil{}},
    ConsumerCons{1n, ConsumerNil{}},
    oauthLeaf,
    profileLeaf,
  }

  messagingLeaf = ScopeLeaf{
    310,
    EnvCons{5n, 311, 8, EnvNil{}},
    ConsumerCons{1n, ConsumerCons{4n, ConsumerCons{5n, ConsumerNil{}}}},
  }
  moderationLeaf = ScopeLeaf{
    320,
    EnvNil{},
    ConsumerCons{2n, ConsumerCons{4n, ConsumerCons{9n, ConsumerNil{}}}},
  }
  sessionBranch = ScopeBranch{
    300,
    EnvCons{4n, 301, 4, EnvCons{1n, 302, 1, EnvNil{}}},
    ConsumerCons{3n, ConsumerCons{4n, ConsumerNil{}}},
    messagingLeaf,
    moderationLeaf,
  }

  ScopeBranch{
    100,
    rootProviders,
    rootConsumers,
    identityBranch,
    sessionBranch,
  }

def report_checksum(report: WiringReport) -> U32:
  match report:
    case Report{scopes, wired, missing, worst}:
      (scopes * 1000 + wired * 100 + missing * 10 + worst : U32)

def main() -> IO(Unit):
  do IO<Unit>:
    topology = sample_reaktor_topology()
    report = evaluate_graph!(topology, EnvNil{})
    checksum = report_checksum(report)
    IO.print(U32.show(checksum))
