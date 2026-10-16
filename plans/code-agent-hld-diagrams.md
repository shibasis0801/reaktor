# Reaktor engineering HLD: implementation and qualification flows

The four views below follow the implementation priority: code intelligence, native cooperation, system ownership and acceptance economics. They describe logical capability and evidence flows, not process deployment or module imports.

**Legend:** green = implemented and qualified for the labelled scope; amber = existing implementation or adapter with live/complete acceptance still open; blue dashed = planned integration or qualification gate; grey = caller or established substrate. Green source edits qualify literal single-file edits and resolved file-local TypeScript rename. Broader rewrites, live model handoffs and measured subscription ROI remain open.

**Kotlin update, 12 October 2026:** an isolated Code-owned Analysis API helper now resolves named definitions and explicit source references for declared common/JVM compilations into the same revision-bound source graph. Real Reaktor Crypto qualification retains 307 occurrences and identical packaged CLI/MCP investigations, with source, common metadata, JVM libraries and JDK hashes checked on cached reuse. Thirty-four focused cases pass. The Kotlin node remains amber because types/calls, broader platform/plugin parity and transformations are still pending. The [qualification receipt](/Users/ovd/dev/tmp/kotlin-semantics-20261012/qualification.json) records the limited scope; daily owners/registrations remain unchanged.

**Live observation, 11 October 2026:** this Codex session connected over MCP to the workspace kernel on port 8766, discovered one tool and 28 operations, queried the current file with a matching SHA-256, retrieved bounded context and confirmed scope/effect rejection. The installed Hangar on port 8765 and the authenticated native owner on port 62150 remain older deployments; the native owner exposes 50 tools without `workspace`. The [live receipt](/Users/ovd/dev/tmp/engineering-kernel-20261011/live-connection.json) preserves these distinctions. The current source cannot be called a fully deployed single-owner system until those running instances are upgraded and reconciled.

Background-first workspace routing, owner identity, exact operator approval files, receipt replay, read-only rejection and lease preservation pass deterministic two-kernel tests. Mounting live Hangar inspection into that remote owner's graph and presenting its receipts in the UI remain open. No active service or harness registration was changed during the live audit.

The current Code path includes Kotlin PSI outlines/identifier tokens and qualified TypeScript SCIP navigation, graph-owned traversal, compact investigation, indexed-target impact and resolved local rename. The kernel consumes CodeWorkspace through the real headless graph provider/consumer port. Current focused acceptance covers thirteen Code, seventeen kernel and eight CLI cases. Earlier thirty-four end-to-end checks passed through copied normal CLI/agent distributions without class overlays or model calls, including existing operator approval, apply/replay, real TypeScript compilation, behavior and re-indexing. The [semantic receipt](/Users/ovd/dev/tmp/code-semantics-20261011/qualification.json) preserves that earlier evidence; the [investigation receipt](/Users/ovd/dev/tmp/code-investigation-20261011/qualification.json) records the additional graph and native acceptance. Active installations have not been upgraded.

The real SCIP helper indexed Reaktor's TypeScript performance target: thirteen source files and seventeen bound local inputs. For the same question, compact investigation replaces four separate reads: 8,172 → 2,008 structured bytes and 23.846 → 5.841 ms warm median over twenty alternating trials per path. The [standard performance report](/Users/ovd/dev/tmp/code-investigation-20261011/performance.json) links the JVM profile; the original measurement retains full precision and a 997.878 ms cold investigation. Two fresh native Codex sessions answered the same bounded question. Shared MCP descriptor guidance reduced discovery overhead from three to one MCP calls and reported fresh input from 18,898 to 11,475 tokens. The [native report](/Users/ovd/dev/tmp/code-investigation-20261011/native-performance.json) retains total and cached usage, generations and elapsed time. One run per variant is directional evidence, not general subscription ROI or Claude acceptance. Executable helper assets remain in the open-source Code checkout rather than the JVM distributions. The newly packaged workspace surface has twenty-nine operations; the live audit above describes older running versions.

### 1. Code intelligence and deterministic transformations

```mermaid
flowchart LR
    scope["Canonical workspace and explicit file scope"] --> identity["SHA-256 source identity"]
    identity --> literal["Bounded literal queries"]
    literal --> packet["Bounded context with explicit omissions"]
    literal --> simple["literal-v1 recipe"]
    identity --> syntax["Kotlin PSI outlines and lexer identifiers: syntax evidence"]
    identity --> scip["SCIP TypeScript 0.4.0 and compiler 5.9.3: source/config-bound target"]
    identity -.-> lsp["Existing LSP adapters: live semantic qualification pending"]
    identity --> kotlin["Kotlin Analysis API: common/JVM named definitions and references qualified; types/calls pending"]
    identity -.-> joern["Joern: isolated flow questions on demand"]
    identity -.-> graphify["Graphify: optional code and document imports"]
    scip --> facts["Code-owned source graph: typed facts, relationships and CodeWorkspace port"]
    facts --> navigation["Compact investigation: selection, definitions, references and affected files"]
    navigation --> packet
    syntax --> packet
    syntax -.-> facts
    lsp -.-> facts
    kotlin --> facts
    joern -.-> facts
    graphify -.-> facts
    facts --> impact["Bounded impact: files with recorded target occurrences"]
    facts --> local["Resolved file-local TypeScript rename"]
    facts -.-> structural["Project-wide and broader structural codemods"]
    facts -.-> rewrite["OpenRewrite or Kotlin PSI recipes"]
    impact -.-> checks["Automatic selective check planning"]
    simple --> proposal["Exact sealed change proposal"]
    local --> proposal
    structural -.-> proposal
    rewrite -.-> proposal
    packet --> proposal
    proposal --> authority["Shared approval, apply and receipt path"]
    syntax -.-> astgrep["ast-grep structural provider qualification"]
    classDef qualified fill:#DCFCE7,stroke:#15803D,color:#14532D
    classDef partial fill:#FEF3C7,stroke:#D97706,color:#78350F
    classDef planned fill:#DBEAFE,stroke:#2563EB,color:#1E3A8A
    class scope,identity,literal,syntax,scip,navigation,facts,impact,local,packet,simple,proposal,authority qualified
    class lsp,kotlin partial
    class astgrep,joern,graphify,checks,structural,rewrite planned
    linkStyle 0,1,2,3,4,5,10,11,12,13,19,20,24,25,28,29 stroke:#15803D,stroke-width:2px
    linkStyle 6,7,15,16 stroke:#D97706,stroke-width:2px
    linkStyle 8,9,14,17,18,21,22,23,26,27,30 stroke:#2563EB,stroke-width:2px
```

### 2. Native agent sessions and A2A cooperation

```mermaid
flowchart TB
    surface["MCP, CLI, typed clients and Hangar"] --> kernel["One graph-owned workspace"]
    kernel --> admission["Scoped admission, request identity and retained tasks"]
    admission --> context["Bounded code context and retained decisions"]
    context --> native["Existing native-session adapters"]
    native --> codex["Codex App Server"]
    native --> claude["Claude Code CLI"]
    native -.-> sdk["Claude Agent SDK: authentication and usage gate"]
    admission --> peer["Bounded A2A 1.0 peer profile"]
    peer --> mapping["SendMessage, GetTask and CancelTask mappings"]
    mapping --> limits["Read-only questions, parent links and child limits"]
    limits --> sessions["Peer delivery through native adapters"]
    sessions -.-> liveProof["Live Claude to Codex and Codex to Claude proof"]
    liveProof -.-> codex
    liveProof -.-> claude
    native --> continuation["Existing checkpoints and provider continuation"]
    continuation -.-> handoff["Fresh-session cross-harness continuation acceptance"]
    codex --> reported["Reported usage and retained results"]
    claude --> reported
    sdk -.-> reported
    reported -.-> economics["Total parent and child consumption per accepted change"]
    handoff -.-> economics
    admission --> mechanics["reaktor-tooling: supervised process and transport lifecycle"]
    classDef qualified fill:#DCFCE7,stroke:#15803D,color:#14532D
    classDef partial fill:#FEF3C7,stroke:#D97706,color:#78350F
    classDef planned fill:#DBEAFE,stroke:#2563EB,color:#1E3A8A
    class surface,kernel,admission,context,mapping,limits,mechanics qualified
    class native,codex,claude,peer,sessions,continuation,reported partial
    class sdk,liveProof,handoff,economics planned
    linkStyle 0,1,2,8,9,21 stroke:#15803D,stroke-width:2px
    linkStyle 3,4,5,7,10,14,16,17 stroke:#D97706,stroke-width:2px
    linkStyle 6,11,12,13,15,18,19,20 stroke:#2563EB,stroke-width:2px
```

### 3. System ownership and agent access

```mermaid
flowchart TB
    agents["Codex and Claude"] --> mcp
    agents --> cli
    agents --> clients
    automation["Scripts and CI"] --> cli
    hangar["Hangar UI and read MCP"] --> kernel
    subgraph privateProduct["BestBuds: closed source"]
        mcp["One workspace MCP tool"]
        cli["Thin CLI: bounded JSON"]
        clients["Native and typed clients"]
        kernel["Kernel over reaktor-graph: typed ports, scope, authority and lifecycle"]
        door["Existing operator approval door"]
        inspection["Graph inspection and configured providers"]
        rollout["Live rollout pending: Hangar and native owner use older interfaces"]
        attachment["Background-first UI inspection mount and receipt presentation"]
        mcp --> kernel
        cli --> kernel
        clients --> kernel
        kernel --> door
        kernel --> inspection
        rollout -.-> kernel
        hangar -.-> attachment
        attachment -.-> kernel
    end
    subgraph openSource["Reaktor: open source"]
        code["reaktor-code: source graph traversal, compact investigation, syntax and local rename"]
        conductor["reaktor-conductor: context, native tasks and continuation"]
        tooling["reaktor-tooling: processes, transports and generic mechanics"]
        graphRuntime["reaktor-graph-runtime: identities, nodes and ports"]
        kernel --> code
        kernel --> conductor
        kernel --> graphRuntime
        conductor --> code
        conductor --> tooling
    end
    kernel --> evidence["Existing linked stores: receipts, checks, decisions and checkpoints"]
    conductor --> evidence
    kernel --> buildFacts["Existing build and check substrate"]
    classDef qualified fill:#DCFCE7,stroke:#15803D,color:#14532D
    classDef partial fill:#FEF3C7,stroke:#D97706,color:#78350F
    classDef planned fill:#DBEAFE,stroke:#2563EB,color:#1E3A8A
    classDef substrate fill:#F1F5F9,stroke:#64748B,color:#334155
    class mcp,cli,clients,kernel,door,inspection,code,conductor,tooling,graphRuntime,evidence qualified
    class hangar,rollout partial
    class attachment planned
    class agents,automation,buildFacts substrate
    linkStyle 0,1,2,3,4,5,6,7,8,9,13,14,15,16,17,18,19 stroke:#15803D,stroke-width:2px
    linkStyle 10 stroke:#D97706,stroke-width:2px
    linkStyle 11,12 stroke:#2563EB,stroke-width:2px
    linkStyle 20 stroke:#64748B
```

### 4. Change verification, continuity and ROI

```mermaid
flowchart TB
    outcome["Outcome and acceptance"] --> query["Bounded current evidence"]
    query --> plan["Exact source-bound proposal"]
    plan --> freshness{"Source still matches?"}
    freshness -->|"Yes"| approval["Operator approves exact effect"]
    freshness -->|"No"| replan["Reject stale plan and query changed inputs"]
    replan --> query
    approval --> idle{"Workspace idle and approval valid?"}
    idle -->|"Yes"| apply["Atomic single-file apply"]
    idle -->|"No"| wait["Return explicit pending or unavailable status"]
    apply --> receipt["Retained receipt and request digest"]
    retry["Retry after uncertain reply"] --> receipt
    receipt -->|"Matching request"| reuse["Return same receipt without repeating edit"]
    receipt --> checks["Existing check execution and candidate evidence"]
    checks -.-> validity["Operation-wide manifests and check reuse validity"]
    validity -.-> continuity["Packaged task continuity and cross-harness handoff"]
    continuity -.-> accepted["Accepted real change with required checks"]
    accepted -.-> measure["Measure tokens, repeats, repairs and human effort"]
    measure -.-> tuning["Keep optimizations that improve accepted work"]
    classDef qualified fill:#DCFCE7,stroke:#15803D,color:#14532D
    classDef partial fill:#FEF3C7,stroke:#D97706,color:#78350F
    classDef planned fill:#DBEAFE,stroke:#2563EB,color:#1E3A8A
    classDef substrate fill:#F1F5F9,stroke:#64748B,color:#334155
    class query,plan,freshness,approval,replan,idle,apply,wait,receipt,reuse qualified
    class checks partial
    class validity,continuity,accepted,measure,tuning planned
    class outcome,retry substrate
    linkStyle 0,1,2,3,4,5,6,7,8,9,10,11 stroke:#15803D,stroke-width:2px
    linkStyle 12 stroke:#D97706,stroke-width:2px
    linkStyle 13,14,15,16,17 stroke:#2563EB,stroke-width:2px
```
