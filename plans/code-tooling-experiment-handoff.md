# Reaktor coding tools experiment and Claude handoff

Prepared 16 October 2026. Tool experiments ran on 13 October 2026; the native task comparison ran on 12 October. This handoff records those experiments, the resulting tool selection and the next implementation slice. It does not claim a new benchmark run or a production rollout on 16 October.

Continue with **Kotlin Analysis API, reaktor-graph, selected ast-grep queries and exact source-range edits** as the default. OpenRewrite remains optional for recipes that demonstrate an advantage. Do not make the external Kotlin SCIP indexer, current Kotlin LSP servers, Joern, CodeQL or Graphify prerequisites for everyday coding.

The product goal is more accepted coding work within the user's native Claude/Codex subscription allowances, with less repeated investigation. Prioritize code intelligence, then native agent integration, then ownership and verification integration. Working functionality comes before generalized resilience; retain correctness and authority checks required by each feature.

## Evidence and implementation status

The experiment used isolated copies, made **zero production edits** and invoked **zero models**. The worker is `m1-worker`; its original experiment directory was still present when checked on 16 October:

`/Users/ovd/dev/offload/kotlin-tools-20261013`

The local temporary reports linked in the earlier chat have expired. A compact durable recovery is now [kotlin-tools-20261013-evidence.json](../build/dependeasy/receipts/qualification/archive/kotlin-tools-20261013-evidence.json). It contains original measurements, scope summaries, recipe outcomes, 31 named qualification assertions and SHA-256 identities of the underlying worker records. All 31 positive assertions replayed successfully over the retained results on 16 October; the original 3,059 authored-source hashes still matched. This was evidence recovery, not another compiler/analyzer run. Negative outcomes remain recorded separately.

Historical CodeQL/Joern and native task raw reports were no longer found in their original local directories. Their results below are prior recorded observations; they were not independently replayed during this handoff. Do not link those expired reports as accessible evidence.

Current source inspection on 16 October found:

- [Kotlin producer](../reaktor-code/tools/kotlin-analysis/Main.kt) still declares Analysis API **2.5.0-Beta2-59**, common/JVM modules, compiler **2.4.21**, language/API **2.4** and JVM **25**. It indexes named source declarations and explicit name references. The experiment's newer calls/types/JS/Native functionality is **not integrated** there.
- [CodeSemanticIndex](../reaktor-code/src/jvmMain/kotlin/dev/shibasis/reaktor/code/CodeSemanticIndex.kt) already owns a `GraphShape`, graph-derived lookup indexes, bounded investigation and recorded symbol relationships. Its omissions explicitly exclude operator/inferred calls and types, compiler plugins and other platforms. Extend this owner rather than creating a second graph database.
- [CodeWorkspace](../reaktor-code/src/jvmMain/kotlin/dev/shibasis/reaktor/code/CodeWorkspace.kt) has the existing edit-plan/apply contract and recognizes `literal-v1` and `scip-local-rename-v1`. The latter is not evidence that project-wide Kotlin rename has shipped.
- [CodeWorkspaceNode](../reaktor-code/src/jvmMain/kotlin/dev/shibasis/reaktor/code/CodeWorkspaceNode.kt) supplies the typed graph port. BestBuds' current consumer is `/Users/ovd/dev/bestbuds/modules/kernel/src/main/kotlin/ai/bestbuds/reaktor/kernel/KernelEngineering.kt`; its `KernelEngineeringNode` exposes the existing single `workspace` MCP surface.
- [Packaging and tests](../reaktor-code/build.gradle.kts) already provide `kotlinSemanticDistZip` and `kotlinSemanticTest`. Ordinary JVM tests exclude `KotlinAnalysisIntegrationTest`, so running `jvmTest` alone does not qualify the isolated compiler helper.

The inspected Reaktor HEAD was `162ca4f3a9f0417a70f69ee3b2274756dfd12be6`, with substantial unrelated uncommitted work. That is the handoff inspection baseline, not the experiment's source revision. Preserve concurrent work. Older HLD completion statements can lag these source facts.

## Experiment design and versions

The frozen corpus contained **3,059 authored Kotlin files**, totaling **19,685,653 bytes**, across Reaktor, BestBuds and Manna. Broad parser scans used this corpus; compiler-level semantic probes used smaller explicit target contexts. These are different coverage claims.

| Component | Executed or reviewed version |
| --- | --- |
| Kotlin Analysis API standalone | **2.5.0-Beta2-71**, latest declared JetBrains development artifact at the 13 October capture |
| Product target compiler | **2.4.21**; language/API 2.4; JVM target 25 where applicable |
| ast-grep | **0.50.0** |
| OpenRewrite | **8.90.4**; advertised 8.93.3 was behind authenticated repository access and was not executed |
| Published SCIP Kotlin plugin | **0.13.1**, fat plugin |
| SCIP source review | `baba91a2482300612abfe67c53184fadd3b5fcac`; reviewed, not built |

Times and peak RSS came from individual worker processes. Some independent jobs overlapped, and there was one machine. Treat these as directional measurements, not universal performance guarantees. No simulators or emulators were started.

## Kotlin Analysis API results

All encountered `KtCallExpression` instances in the following declared scopes resolved, and every scope had zero reported semantic errors under the probe's configured checker filter. Resolution counts establish coverage of these expressions; the named assertions check selected meanings, not every possible Kotlin construct.

| Declared scope | Files | Resolved calls | Cold process | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Reaktor Crypto common/JVM | 2 | 96/96 | 3.70 s | 395 MiB |
| Reaktor Crypto common/JS | 2 | 108/108 | 3.15 s | 395 MiB |
| iOS Foundation interop | 1 | 5/5 | 2.09 s | 410 MiB |
| Graph ports and adversarial client | 18 | 520/520 | 5.54 s | 466 MiB |
| BestBuds JVM model | 1 | 19/19 | 2.81 s | 348 MiB |
| Manna models/date library | 3 | 101/101 | 2.99 s | 381 MiB |
| BestBuds Compose | 61 | 3,992/3,992 | 11.11 s | 778 MiB |

Total: **4,841/4,841 call expressions**, with some source overlap across target contexts. Do not describe this as whole-repository semantic indexing.

Specific successful checks included:

- Distinct `ConsumerPort.invoke` overload identities and inferred `String` versus `Greeter` results.
- Suspend calls and generic return inference.
- An import alias resolving to the same extension declaration, while an unrelated member and a local function-valued variable remained distinct.
- JDK `Cipher.doFinal`, JS Promise/`await` and a common Kotlin property referenced from JS.
- Native Foundation `NSData` and `kotlinx.cinterop.readBytes` with the expected nullable byte-array result.
- BestBuds `sumOf` overloads, Manna `kotlinx.datetime.LocalDate.parse`, and Compose annotated slot/class references.

The Compose scope contained **57 authored plus four actual generated Kotlin resource files**, with **104 dependency artifacts**. The original run without generated resources reported 168 missing-resource errors; supplying the four generated files removed the errors. The initial 168 count is recorded session evidence; its overwritten diagnostic JSON is not independently retained. Production target discovery must include generated sources and exact dependencies.

Real compiler checks also passed for Crypto JVM, Crypto JS, an `ios_arm64` library and the graph fixture. This did not qualify every Compose target or every Android/Native module.

After loading the graph scope, repeated resolution of 29 cached calls had a median of **0.096417 ms**, excluding the first query. This is in-process cache work after analysis, not MCP/kernel latency, a persisted-cache benchmark or token savings.

Remaining coverage: Android variants, larger Native `expect/actual` hierarchies, compiler-plugin-heavy modules, all required target combinations, and useful incremental reuse. Flatten or serialize semantic facts inside their `KaSession`; do not retain live session-bound symbols as durable graph facts. The probe also avoided requesting source offsets from binary Java PSI, which caused an initial standalone harness failure.

## ast-grep and exact-edit results

The error-node scan covered **3,059 files in 2.10 s**, with **60,456,960 bytes peak RSS** (57.7 MiB). It reported **516 grammar ERROR nodes in 154 files**: Reaktor 70, BestBuds 81 and Manna 3. These are parser limitations, including Compose-related syntax, not proof that those sources fail Kotlin compilation.

The functional migration renamed the real graph extension `addInterceptor` to `addPortInterceptor` over **17 graph/core sources plus one adversarial client**:

1. ast-grep produced **eight** identifier candidates.
2. Analysis API resolved the declaration and references, qualifying **four** edits and rejecting **four** unrelated occurrences.
3. A small patcher applied exact UTF-8 byte-range replacements after converting Analysis API's UTF-16 offsets.
4. The declaration, regular call, import and alias import changed. The alias call, unrelated member declaration/call, local variable/call and explanatory comment remained intact.
5. Compilation and behavior passed. Re-analysis found **zero old target references and four new references**, so a qualified second pass had no work.

Nine preservation assertions passed. Candidate selection, copying and patching took about **745 ms** in that worker run; compiler/analysis time is separate. The demonstrated change was not a global spelling replacement.

**sed itself was not executed.** The evidence supports compiler-guided range edits. Broad `sed` substitution would also select unrelated names in the fixture. Use literal tools for explicitly scoped textual changes; use semantic identity where a change means a particular Kotlin symbol.

This is enough evidence to build the first useful lightweight codemod path. It does not establish that arbitrary signature migrations, file moves or complex rewrites already work. Use Kotlin PSI where ast-grep cannot parse the relevant syntax; avoid making grammar failures silently disappear from coverage.

## OpenRewrite results

The final broad parse/print run processed **3,059 files** with a shared JVM classpath. **3,057** parsed/printed successfully; `Theme.kt` and `ComposeBindings.kt` were parse-error files. There were zero other print mismatches and zero final harness context errors. The process took **44.54 s**, peaking at **5,766,103,040 bytes RSS** (5.37 GiB).

This broad scan establishes syntax/printing coverage, not semantic qualification of every KMP target. Its workload differs from ast-grep's error-node scan; do not turn the two elapsed times into an apples-to-apples speedup claim.

Two focused recipes used the actual graph JVM dependency context and compiled baseline classes:

- **`ChangeType`: `PortInvocation` to `PortCall` passed.** It changed declarations, constructors, parameter types and overrides across four files. Compilation, port behavior and a second pass with zero changes all passed.
- **`ChangeMethodName`: the extension rename failed.** The exact facade matcher produced no edits because the attributed facade contained a source-path prefix. A widened matcher, with `ignoreDefinition=false`, changed a regular call but left the declaration/imports unchanged. Compilation failed. Its second pass also reported zero edits, demonstrating that idempotence alone does not establish correctness.

Initial missing SLF4J and Java 25 parser/template runtime dependencies were repaired before the final recipe runs. Do not report those harness failures as unsupported Kotlin features.

Decision: no mandatory OpenRewrite dependency in the default path. Retain it as an optional recipe engine when an existing tested migration saves enough custom implementation work. The successful type recipe has not been reproduced by the lightweight stack, so full equivalence remains unproven.

## SCIP, LSP and Graphify selection

**SCIP Kotlin:** Kotlin support exists. The published 0.13.1 plugin failed immediately on both JVM and JS with compiler 2.4.21: `AnalyzerRegistrar` was incompatible because `CompilerPluginRegistrar.getPluginId()` was missing (`AbstractMethodError`). Baseline compilations without the plugin passed. No Kotlin index was produced by that plugin. Reviewed upstream main contained newer compatibility changes, but its KMP Gradle triggers were JVM-oriented and main was not compiled in this experiment. Skip maintaining a plugin fork or a broad indexer integration project.

SCIP as an interchange shape remains useful independently. The existing Reaktor Analysis API producer already emits a `reaktor-scip-v1` artifact. Avoiding the external Kotlin indexer does not require deleting that format or breaking working TypeScript support.

**LSP:** documentation/source coverage review only; no server was launched. As reviewed on 13 October, the official Kotlin LSP listed KMP as future support. The reviewed `Hessesian/kmp-lsp` documented word-based `rg` references and syntax-only diagnostics without compiler type checking. These providers did not meet the user's required KMM/Reaktor+ semantic scope. The protocol can remain an optional projection; it is not the semantic engine.

**Graphify:** documentation/source review only; no runtime benchmark. Its code path used deterministic tree-sitter extraction, while document/media relationships could be model-derived. Do not characterize all Graphify edges as LLM output. Its code graph did not demonstrate incremental value over our Kotlin PSI/API and existing graph ownership. A document relationship experiment needs a concrete question existing Reaktor/Manna capabilities cannot answer.

These are dated selection results, not statements that future releases can never qualify.

## Earlier CodeQL and Joern observations

These prior results were recorded before the Kotlin tool experiment; their original local raw reports have expired.

| Probe | CodeQL | Joern |
| --- | --- | --- |
| Versions | CLI 2.27.2; Java queries 1.11.12 | 4.0.654; Kotlin frontend 2.3.21 |
| Synthetic flow controls | 7/8; missed `listOf.forEach` callback flow | 8/8 |
| Seeded crypto controls | 4/4 | Separate flow experiment |
| Actual common/JVM Reaktor Crypto | Extraction was not performed | `ReachingDefPass` crashed on a `METHOD_REF` associated with `constantTimeEquals` |
| Actual JVM-only JCA lineage | No equivalent production result established | 6/9; nested coroutine/AAD and false-path cases exposed limitations |

Recorded cold extraction was approximately 12.75 s for CodeQL and 3.35 s for Joern; first queries 41.62 s and 4.21 s, with repeated invocations around 3.87 s and 4.24 s respectively. Loaded Joern answered a small eight-query probe in roughly 100 ms. Different caching/initialization states matter. These timings do not establish end-to-end agent RoI.

Augment reaktor-graph for Kotlin calls/types, declared provider/consumer wiring, modules, capabilities, effects and affected checks. Import bounded specialist findings when they answer a missing flow/security question. Ordinary graph traversal does not supply alias-aware taint, sanitizers, control-flow extraction, coroutine/callback models or runtime dispatch. Do not build a general replacement for CodeQL/Joern just to avoid dependencies.

## Agent performance and token evidence

The 12 October matched native Codex experiment renamed `Sealed.nonce` to `iv` across two real Crypto files. The recorded comparison used two matched pairs, and all four attempts passed compile/behavior verification:

| Recorded median | Without tooling | With tooling |
| --- | ---: | ---: |
| Fresh input tokens | 15,715.5 | 27,589.5 |
| Elapsed time | 21.705 s | 27.926 s |

Tooling consumed approximately **76% more fresh input and 29% more time** in that small comparison. Agents read complete files and then added graph context. Tooling supplemented work instead of replacing it. Raw comparison records have expired locally; do not treat these medians as a new measurement on 16 October.

The existing [HLD](code-agent-hld.md) separately records a bounded-question experiment: a compact investigation reduced structured output from 8,172 to 2,008 bytes and warm median work from 23.846 to 5.841 ms. One native sample per variant reported 18,898 to 11,475 fresh input tokens after direct operation execution reduced discovery calls. That narrower question and the later coding task are different experiments; neither establishes general subscription savings or Claude parity.

Next comparisons must measure accepted tasks on both native agents. Tool-assisted context must replace repeated reads. Record total and cached input, output, elapsed time, tool calls, repeated reads, setup cost, correctness and any review/rework. Do not infer quota savings directly from bytes, parser speed or warm semantic lookup time.

## Next implementation slice

1. **Promote useful Kotlin semantics into the existing producer.** Add resolved call sites, inferred types, receivers and overload identities from the probe. Consume exact source-set/platform/dependency context, including generated Kotlin and Native klibs. The current source reader forbids arbitrary `build` paths, while generated sources are necessary: bind declared generated artifacts explicitly rather than removing containment checks. Qualify Android and broader Native cases before advertising those scopes. Keep the compiler isolated and distinguish helper version from product compiler version.
2. **Extend existing graph facts and investigations.** Feed the current `CodeSemanticIndex`/`GraphShape`; preserve source, target and binary identity. Keep declared port wiring distinct from compiler-resolved calls and uncertain runtime dispatch. Add bounded impact/check selection only where the recorded edges justify it. Reuse facts across Claude, Codex and Hangar rather than indexing anew per question.
3. **Ship one compiler-guided codemod through the existing change path.** Start with the proven extension rename. Reuse `CodeEditPlan` and the existing proposal/apply/receipt owner. Preserve aliases, overloads, local shadows and unrelated names; qualify Unicode/UTF-16/UTF-8, CRLF and import cases. Test a real bounded change through the shared kernel, with compilation and behavior evidence. Add other transformations when real tasks justify them.
4. **Make the agent consume the integrated result.** Extend BestBuds' existing graph-owned `workspace` operation and shared CLI/typed clients. One investigation should return symbol identity, relevant definition/calls, bounded context, impact and explicit omissions. Known operations should execute directly; avoid repeated discovery/schema calls. Reuse native agent adapters and retained task evidence. Run matched accepted-task comparisons on Claude and Codex before reporting RoI.

The reusable code, conductor and tooling capabilities remain open source in Reaktor. Product CLI/MCP/kernel composition remains closed source in BestBuds and shared with Hangar. Use the existing graph runtime, typed ports and lifecycle; add no second scheduler, graph store, generic executor or standalone MCP server for this slice. The build layer is consumed infrastructure, not the redesign target.

The first completion gate is a real Kotlin task that an agent investigates, proposes, applies through the existing authority path and verifies using this system, with less repeated discovery and an evidence-backed comparison against the ordinary workflow. Do not block this on generalized resilience work.

## Worker reproduction and continuation pointers

The frozen worker directory still contained the scripts, classes, libraries, manifests, sources and original result/log files on 16 October. Inspect before executing; commands overwrite result names. Do not run `prepare.py` over the frozen directory: it copies current repository sources and relies on older scratch dependencies that may have expired. A new dated experiment needs its own declared input capture and storage budget.

Representative commands in the existing worker directory are:

```sh
cd /Users/ovd/dev/offload/kotlin-tools-20261013
python3 run-analysis.py crypto-jvm crypto-js foundation-ios graph-jvm bestbuds-jvm manna-jvm bestbuds-compose
python3 ast-probe.py
python3 ast-migration.py
python3 compile-targets.py graph ast/after
python3 run-analysis.py graph-after
python3 run-rewrite.py graph-type graph-wildcard
python3 compile-targets.py graph rewrite/after-graph-type
python3 compile-targets.py graph rewrite/after-graph-wildcard
```

The final extension-rename compilation is expected to fail. `run-analysis.py` does not propagate every child failure as its own exit status, so inspect each retained `*-measurement.json` and diagnostics. The Native compilation command is retained in `compile-foundation-ios-measurement.json`. Compilation of the probes themselves used separate compiler and runtime classpaths; inspect retained measurement command arrays if rebuilding them.

Read `AnalysisProbe.kt`, `RewriteProbe.java`, `ast-migration.py`, `prepare-targets.py` and `prepare-compose.py` for the exact experimental setup. `corpus-inputs.json` binds authored sources; the semantic manifests bind actual platform libraries. No full source copy or heavyweight tool distribution was added to this handoff.

For production qualification, use the repository's `./gradlew` load-routing wrappers. Relevant existing targets include `:reaktor-code:kotlinSemanticTest`, `:reaktor-code:jvmTest` and `:reaktor-code:verifyCodeBoundary`; add consuming-kernel tests for operations you change. These were inspected, not rerun during handoff preparation. Respect current AGENTS instructions, keep `.remote-dev` below 20 decimal GB and combined temporary work below a separate 20 decimal GB, and run `~/dev/tools/dev-storage cleanup` before large scratch/build work and before finishing temporary work. Do not start emulators or simulators or revert unrelated changes.

Historical primary-source references, captured for review rather than a claim of current release status:

- [Analysis API artifact metadata](https://packages.jetbrains.team/maven/p/kt/dev/org/jetbrains/kotlin/kotlin-analysis-api-standalone-implementation/maven-metadata.xml) and [call resolution](https://kotlin.github.io/analysis-api/resolving-calls.html).
- [Reviewed SCIP Gradle integration](https://github.com/scip-code/scip-java/blob/baba91a2482300612abfe67c53184fadd3b5fcac/scip-gradle-plugin/src/main/java/org/scip_code/scip_java/gradle/ScipGradlePlugin.java).
- [Official Kotlin LSP coverage](https://github.com/Kotlin/kotlin-lsp#supported-features) and [community KMP server limitations](https://github.com/Hessesian/kmp-lsp/blob/main/docs/features.md).
- [OpenRewrite Kotlin recipes](https://docs.openrewrite.org/recipes/kotlin), [ChangeType](https://docs.openrewrite.org/recipes/java/changetype) and [published versions](https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module).
- [Graphify extraction model](https://github.com/Graphify-Labs/graphify).
