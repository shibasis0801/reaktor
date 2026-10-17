# Scoped code work

`reaktor-code` owns renderer-independent editing contracts and a JVM `CodeWorkspace` for explicit-file source work. Hosts provide it through Reaktor graph ports. The module has no tooling, MCP, language-server or UI dependency.

`read(path)` validates a workspace-relative regular UTF-8 file and returns its SHA-256 content identity. `query(paths, literal, limit)` searches up to 128 explicit files, returns useful match excerpts and reports partial output. Paths cannot traverse outside the canonical workspace or through symlinks, generated build directories or `.env`. Files are bounded to 512 KiB.

`outline(path, limit)` parses `.kt` and `.kts` with the pinned Kotlin compiler's PSI. It returns package/imports, nested declarations, overload signatures, declaration/name ranges and syntax diagnostics. Local body declarations are omitted. Ranges use zero-based lines and UTF-16 columns with exclusive ends, including CRLF source. Broken syntax and omitted output are marked partial; the digest always identifies the original bytes.

`identifiers(paths, name, limit)` searches Kotlin identifier tokens, including backtick names and string interpolation, without matching plain string/comment text or longer names. Like literal queries, it returns at most one excerpt per matching line; those line numbers are one-based. It does not resolve which declaration a use refers to. The parser starts only for outline requests; ordinary reads and literal queries do not initialize it.

`plan(path, find, replacement)` produces a deterministic `literal-v1` proposal bound to the canonical workspace, source path and before/after content. It rejects ambiguous overlap and non-idempotent replacements. `apply(plan, approvedFingerprint)` checks the proposal seal and source again, preserves file permissions and atomically replaces one file. The host must admit authority before calling it. The source check is not a global lock against unrelated editors or processes.

BestBuds' closed engineering kernel supplies the `workspace` MCP tool and matching CLI/native projection. It requests a single-use operator approval, excludes active agent work and retains running/terminal receipts in the existing control plane. The source module does not grant authority, start models or create another receipt database.

`index(path)` consumes a `reaktor-scip-v1` artifact produced by the Code-owned executable helper. It checks canonical workspace identity, UTF-16 ranges and the SHA-256 of every bound local source/configuration input on each request. A single immutable parsed index is reused while the artifact digest stays unchanged. The artifact is bounded to 16 MiB; each input retains the 512 KiB source limit. Changed inputs require re-indexing.

`CodeWorkspaceNode(graph, root)` provides the workspace through a real typed Reaktor port. JVM callers use the headless `reaktor-graph-runtime` and Java 25. The kernel consumes that port; the source module remains open source and has no product or MCP dependency.

`index(path).query(source, position, kind, limit)` resolves `definition`, `references`, `impact` and `graph` queries from an exact indexed symbol position. The immutable `GraphShape` owns file/symbol identities, typed definition/reference edges and recorded symbol relationships; bounded queries traverse those relationships with private lookup indexes. Symbols are source facts in an inactive scope, rather than individually activated runtime nodes. `sourceGraph(result)` selects the same graph facts for inspection. Results carry index, compiler, provider, target and source identities, useful source lines, total occurrence counts and omissions. Impact identifies files with recorded occurrences in this target. Other targets, external package contents, runtime dispatch and automatic check selection are outside that evidence.

`index(path).investigate(source, position, limit)` combines the selected symbol, definitions, references and affected-file counts in one query. Counts cover all recorded related occurrences before the detail limit; the selected occurrence is retained first. The shared `code.investigate` operation returns this evidence within 12,000 serialized characters, shares file identities and source excerpts, and reports every omission. Pass the index artifact's path as input; reading its contents into an agent context is unnecessary. A limited response does not imply limited impact or qualified project-wide rename.

`index(path).symbols(name, path?, limit)` selects exact identifier spellings through those same graph occurrence edges. It groups overloads that share an identity and keeps distinct shadowed bindings as separate candidates. The optional path restricts where the spelling occurs, rather than asserting a global qualified name. `investigate(symbol, limit)` consumes an exact returned identity. Through `code.investigate`, `{index,name,path?,limit?}` automatically investigates a unique identity; otherwise it returns `needs-selection` with bounded candidates and complete candidate/omission counts, or `not-found` within the indexed target. Choose a candidate with `{index,symbol,indexDigest,limit?}` using the returned revision. Mixed selectors and stale index digests are rejected. Position-based requests remain available.

`renameLocal(index, path, position, newName)` creates a sealed `scip-local-rename-v1` proposal for resolved file-local TypeScript symbols. It edits only the recorded occurrences, preserving comments, strings, shadowed bindings, Unicode positions and CRLF. This recipe requires `scip-typescript/0.4.0` with TypeScript `5.9.3`; it rejects global/project-wide symbols, collisions, coupled shorthand-property ranges and unsupported names. The index digest and exact selector are sealed alongside the source identities. The existing host approval, write admission and operational receipt path applies the proposal; retries return the retained receipt without needing to re-index the already transformed source.

The helper and its exact npm lockfile live in this open-source module. Node 22 and a one-time explicit dependency installation are required; execution performs no installation or model call. The helper qualifies one compiling TypeScript target without project references, records local compiler inputs and checks that they remain unchanged during indexing. The compiler/tool identities are producer metadata, rather than signed attestations. The imported artifact must remain inside the workspace for queries. The executable assets currently run from the Code checkout and are not bundled into the JVM distributions.

```sh
cd reaktor-code/tools
npm ci --ignore-scripts
node scip-index.mjs /absolute/workspace path/to/tsconfig.json /absolute/workspace/analysis/index.json
```

Through BestBuds' single workspace surface, use `operation.schema` to discover the exact arguments. Semantic positions use zero-based lines and UTF-16 columns with exclusive range ends, unlike the one-based match lines of literal/identifier searches.

```sh
reaktor --workspace /absolute/workspace workspace code.query --args '{"kind":"references","index":"analysis/index.json","path":"src/example.ts","line":12,"column":8,"limit":20}'
reaktor --workspace /absolute/workspace workspace code.investigate --args '{"index":"analysis/index.json","path":"src/example.ts","line":12,"column":8,"limit":20}'
reaktor --workspace /absolute/workspace workspace code.investigate --args '{"index":"analysis/index.json","name":"compute","limit":20}'
reaktor --workspace /absolute/workspace workspace context.query --args '{"kind":"references","index":"analysis/index.json","path":"src/example.ts","line":12,"column":8,"limit":20}'
reaktor --workspace /absolute/workspace workspace code.plan --args '{"recipe":"scip-local-rename-v1","index":"analysis/index.json","path":"src/example.ts","line":12,"column":8,"newName":"computed"}'
```

The isolated Kotlin helper produces the same artifact and graph facts from explicit compilation inputs. This Code artifact reuses the SCIP-shaped JSON envelope; it is not an official Kotlin SCIP index. Its Analysis API/compiler is pinned separately to `2.5.0-Beta2-59`; it analyzes declared Kotlin `2.4.21` targets with language/API `2.4` and JVM `25`. This is qualification of the declared common/JVM source model, not equivalence to the project's 2.4.21 compiler or all KMP platforms/plugins. No Analysis API or IDE dependencies enter the ordinary Code/kernel classpath. The compiler field reports both the actual analyzer and declared target settings. Kotlin binary-binding checks require the updated Code consumer; validation through a freshly packaged owner does not upgrade existing running owners or harness registrations.

Build its standalone archive once, then execute it directly from the checkout or extract the archive and invoke its `run` script. The launcher unpacks into a temporary directory and removes it on exit. A canonical Java 25 installation is required; `REAKTOR_JAVA_HOME` selects it outside macOS. Execution performs no download, Gradle build or model call.

```sh
./gradlew :reaktor-code:kotlinSemanticDistZip
reaktor-code/tools/kotlin-index /absolute/workspace analysis/kotlin-target.json /absolute/workspace/analysis/kotlin-index.json
```

The target manifest enumerates each source file exactly once and orders dependency source modules before their consumers. `dependsOn` models common-to-platform refinement; `dependencies` models regular source-module dependencies. Each module declares its own canonical binary classpath: common metadata KLibs for common modules, JVM jars for JVM modules. Use the already resolved compilation inputs; JVM jars alone are insufficient common metadata. Binary directories and implicit source-root scanning are unsupported. Unknown options are rejected so compiler plugins, custom flags, other language/API versions and other platforms cannot be silently ignored.

```json
{
  "format": "reaktor-kotlin-target-v1",
  "compiler": "kotlin/2.4.21",
  "languageVersion": "2.4",
  "apiVersion": "2.4",
  "jvmTarget": "25",
  "jdkHome": "/canonical/jdk25",
  "modules": [
    {"name":"commonMain", "platform":"common", "sources":["src/commonMain/kotlin/Api.kt"], "classpath":["/canonical/stdlib-common.klib"]},
    {"name":"jvmMain", "platform":"jvm", "sources":["src/jvmMain/kotlin/Use.kt"], "classpath":["/canonical/stdlib.jar"], "dependsOn":["commonMain"]}
  ]
}
```

The producer rejects semantic errors and verifies inputs again before atomically publishing the artifact. It records named source declarations and explicit name references resolving to their named source PSI anchors. Source-set/path/anchor identities distinguish overloads and shadowed locals within the bound revision. External, synthetic, constructor and operator references are not complete; types, calls, expect/actual or override relationship expansion, inferred dispatch and Kotlin transformations remain unqualified. Queries explicitly report this limited coverage, and Kotlin indexes cannot invoke the TypeScript rename recipe.

`binaryInputs` binds canonical classpath files and the JDK's `release` and `lib/modules` bytes with SHA-256. `index(path)` validates them on every request, including cached-index reuse. The first implementation deliberately reads those bytes each time; its latency must be included in ROI measurements. Artifacts without binary bindings remain compatible for the existing TypeScript helper; Kotlin artifacts require them. Source/configuration and binary changes both require re-indexing.

```sh
reaktor --workspace /absolute/workspace workspace code.investigate --args '{"index":"analysis/kotlin-index.json","name":"compute","limit":20}'
./gradlew :reaktor-code:kotlinSemanticTest :reaktor-code:jvmTest :reaktor-code:verifyCodeBoundary
```

Literal and Kotlin syntax evidence do not establish resolved symbol identity. Live LSP semantic qualification, Joern, OpenRewrite, ast-grep and Graphify integration remain open. Qualified navigation does not imply project-wide refactoring safety.

```sh
./gradlew :reaktor-code:jvmTest :reaktor-code:verifyCodeBoundary
```
