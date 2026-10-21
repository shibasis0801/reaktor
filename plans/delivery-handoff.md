# Delivery layer: Dagger, Pulumi and deploys — review and next steps

Written on 14 October 2026. This continues `dependeasy-handoff.md`: its work packages are numbered WP19–WP26, after that plan's WP11–WP18. The reporting rules in §9 of that plan apply here too. This file is not published.

## Status

| WP | Package | State |
|---|---|---|
| WP19 | Fix what is broken, delete what is dead | Done: 16 real dev bundles and owner-approved live deployments pass; all 16 active Cloudflare versions match. Five prod-only workers excluded. Cleanup, current command checks, pinned Pulumi and read-only server preconditions qualify. Receipt metadata cache regression fixed; all 5 receipt/bridge cases pass. |
| WP20 | One product delivery declaration | Working: canonical contract path/readers and shared config merge qualify (37 BestBuds configs preserved; 102 copied defaults removed). Manna fleet replaces standalone deployments: 9 fixtures, 2 final configs and warm generation/graph cache pass; committed/pushed. Typed delivery facts, remaining inventories and single environment input remain. |
| WP21 | One deploy contract: plan, apply, verify, rollback, receipt; Cloudflare first | Outstanding |
| WP22 | Dagger as a thin container host; local CI | Outstanding |
| WP23 | Server releases by image digest | Outstanding |
| WP24 | Data changes as nodes: secrets and migrations | Outstanding |
| WP25 | One infrastructure-as-code path: Pulumi in Kotlin | Outstanding |
| WP26 | Consumers read only the model and receipts | Outstanding |

## 1. Verdict

Delivery today is four separate systems, and only one of them is in the build model.

1. **Cloudflare Workers: in the model, but scattered around it.**
   - There are 41 deploy nodes.
   - 11 hand-written worker lists duplicate the fleet.
   - Each worker has two deploy aliases, and environments are selected in two ways.
   - Secrets and migrations sit outside the model.
   - A deploy leaves no plan and no receipt that links it to the Cloudflare version it created.
2. **The JVM server on k3s: entirely outside the model.**
   - Four paths change the `app-server` deployment.
   - Images use mutable tags.
   - The release file that the reviewed rollout depends on is written by nobody and is out of date.
3. **Observability: built twice.**
   - One copy is a Kotlin Pulumi program that nobody runs: it has no stacks, and ~25 config keys are documented nowhere.
   - The other is the live Python-and-Helm path, with its own folder, its own collector and all 17 alerts.
4. **Dagger and CI: the mechanics work, but the logic sits in the wrong layers.**
   - The TypeScript module holds the apt package lists, shell snippets and wrapper rewriting.
   - `pipeline.json` copies the target list, so it changes with every node.
   - "Deploy" is hard-wired to mean a Cloudflare token.
   - Runs return only stdout, with no receipts and no outputs.
   - Only bestbuds has CI, and only for manual dispatch.

On top of these, three operation catalogs and five discovery paths answer the question "what can be deployed?". Each answers it differently.

The fix is one model, one deploy contract and one receipt.
- Every change to a live system becomes a model node: a worker, an image rollout, a migration, a secret set or an infrastructure stack.
- Each such node can say what it will change (plan), do it (apply), check it (verify) and undo it (rollback).
- Each run leaves a receipt that names the remote version it produced.
- Product facts live in one Kotlin declaration per product.
- Dagger only runs nodes in containers.
- Hangar, the CLI and MCP read only the model and the receipts.

## 2. What to keep

- `WorkerFleet`, including its generated final Wrangler configs and its service-binding order edges.
- The pinned toolchain contract that Dagger builds its container from: Java image, Gradle archive with sha256, and Android SDK.
- The pinned Pulumi CLI in `PulumiTool.kt` and the generated Grafana SDK.
- The Kotlin Pulumi program in `reaktor-cloud/observability` as the direction, per the "Kotlin/JVM everywhere possible" rule.
- `rollout-reviewed-image.py`'s policy: digest compare-and-swap, `maxSurge == 0` and automatic rollback. The policy is right; only its language and home are wrong.
- `ci.yml`'s stance: no build commands, only a `dagger call`, and production changes stay manual.

## 3. Findings by domain

### 3.1 Cloudflare Workers

- **Worker lists:** 11 hand-written lists repeat the fleet.
  - the two secrets scripts (14 and 5 workers);
  - `BestBudsSystemTopology.kt:131`;
  - karate `karate-config.js` and `dev.env`;
  - `pnpm-workspace.yaml`;
  - `surface-coverage.spec.mjs`;
  - `cloudflare-resource-scope.json` and the exporter YAML;
  - four Kotlin app maps: `Servers.kt`, `AppEnvironment.kt`, `ConfigService.kt`, `ConfigWorker.kt`.

  `DEPLOY.md` and `AGENTS.md` also carry lists, with 12 workers each.
- **Fleet contract:** `build/dependeasy/cloudflare/fleet.json` has no reader outside dependeasy's tests. It sits outside `contracts/`, and manna does not have one.
- **Two aliases per worker:**
  - `deployX` runs the target with its prerequisites.
  - `xWorkerDeploy` runs Wrangler raw and skips `workerBundle` and `webBuild`.
- **Two environment inputs:** `dependeasy.environment` and `TARGET_ENV`. The secrets scripts honour only `TARGET_ENV`.
  - `WorkerFleetTask` hard-codes `"dev"`.
  - `environments.last()` silently means prod.
- **Config merging:** dev configs skip the root merge, so dev files must be complete while prod files rely on the merge.
  - `account_id`, `compatibility_flags` and `observability` are still copied into all 20 prod sources.
  - `compatibility_date` has 5 different values.
  - 4 workers have no dev twin.
- **Not modelled:** secrets, Postgres migrations, D1 migrations and ClickHouse SQL are not nodes.
  - The bestbuds D1 migration folders have no scripted remote apply.
  - manna's D1 apply is a package script.
- **No plan, receipt or rollback:**
  - no dry run and no diff against the remote;
  - `wrangler deploy` runs with no `--message`;
  - receipts record neither the Cloudflare version id nor a config digest;
  - rollback is a manual recipe.
- **manna:** it uses hand-written `target("deployWeb"/"deployApi")` instead of `workers {}`, so it has no fleet contract, dev environment or logs node.
- **Broken:**
  - `APP_SERVICE` binds `app-service` in 4 prod workers. That service is not in the fleet, and the order edges drop it silently.
  - `TARGET_ENV=dev` on `deployWorkers` fails partway through, at the four prod-only workers.
  - Three workers run `wrangler dev --remote` against the prod config.
  - The Deploy pane matches rows by `--workspace=`, which generated scripts never contain (likely matches nothing; verify first).
  - `appBrowser` has a Wrangler config but no target.

### 3.2 JVM server and k3s

- **Four ways to change `app-server`:**
  - `reaktorServer/deploy.sh`: mutable tag plus `:latest`, `set image`, no rollback;
  - `rollout-reviewed-image.py`: digest compare-and-swap with rollback;
  - the manual recipe in `k3s/README.md:66-77`;
  - `observability/deploy.sh jvm` with `jvm-otel-patch.yaml`.
- **Nothing in the model:** `bootBuildImage`, the push, the image digest and the rollout are not nodes. `:reaktorServer` has two nodes, both module outputs. `springImage("…:latest")` uses a mutable tag.
- **The release file is stale:**
  - `release.json` (8 Oct) expects `:20261005-manna-journal-docs`.
  - The uncommitted `app-server.yaml` pins a sha256 digest.
  - Nothing writes `release.json`, so the reviewed rollout will refuse.
- **Product facts are spread across files:**
  - `mehmaan-app`: 26 files;
  - the registry `asia-south1-docker.pkg.dev`: 9;
  - `bestbuds-app-server`: 8;
  - namespace `reaktor`: 46 lines in 22 files;
  - `app-server`: 53 lines in 12 files;
  - `reaktor-vm` and the zone: 13 files.

  The kubeconfig path is resolved in five different ways.
- **Provisioning:** `cloud/k3s/deploy.sh` is a VM bootstrap, not a deploy. `vm-setup.sh` downloads a `secrets.yaml` that `deploy.sh` never copies, so a fresh bootstrap would fail.
- **Unpinned images:** `cloudflared:latest` and `node-exporter:latest`.

### 3.3 Observability and Pulumi

- **The Pulumi program is never run.** It is 433 lines in 8 Kotlin files.
  - It has no stack files and no `.example`; ~25 `cfg.require` keys are documented nowhere.
  - `PulumiToolProvider` uses whatever `pulumi` is on PATH, not the pinned CLI.
  - The provider is constructed only in an e2e test against a fake executable.
- **The live system is the second copy.**
  - Grafana folder: `sync-grafana.py` writes to folder `reaktor-bestbuds`; Pulumi would create "Reaktor".
  - Collector: Pulumi installs the `k8s-monitoring` chart into `monitoring`, while `observability/deploy.sh` installs `reaktor-alloy` into `observability`. Running both would double-scrape.
  - Cloudflare metrics: Pulumi uses an Infinity datasource, while the live side has a 1,005-line exporter and a 66-panel dashboard.
  - Alerts: Pulumi has none; the 17 alerts exist only on the Python side.
  - The observability README itself warns against running the Pulumi program because it would duplicate resources.
- **Chart versions** are pinned outside `ToolchainVersions`: `K3s.kt`, `observability/deploy.sh` and `radar.sh`.
- **Mixed layers:** Pulumi modules do placeholder string replacement in the same function that creates resources.

### 3.4 Dagger and CI

- **Shared module:** `reaktor/dependeasy/dagger/src/index.ts` is 189 lines and holds decisions that belong in Kotlin:
  - the apt package list;
  - a curl-plus-sha256 shell snippet;
  - `sed` rewriting of both Gradle wrapper files;
  - cache volume names, the `/work/<build>` layout and PATH assembly;
  - `"prod"` as the default environment;
  - "a Deploy needs a Cloudflare token";
  - a `runner === "browser"` branch that no node produces (all 49 targets are `gradle`).
- **`pipeline.json`:** it copies the target list. bestbuds lists 40 targets, including karate suites duplicated per environment by name (`workerKarateAuthDev`/`Prod`).
  - Adding any node rewrites a tracked file.
  - `verifyDagger` then polices that copy.
- **`DaggerModule.kt`:**
  - JVM memory flags travel as one long `GRADLE_OPTS` string;
  - the browser image is a hard-coded `var`;
  - it writes `dagger.json`, `package.json` and `tsconfig.json` as JSON literals;
  - it records itself through the string-keyed `taskFamilies` side channel.
- **Run output:** `run` returns stdout only. Receipts written inside the container are discarded, and so are the outputs of build nodes such as `webArtifacts` and `androidArtifacts`.
- **CI coverage:** only bestbuds has CI, and it runs on manual dispatch only.
  - The deploy job deploys workers only.
  - reaktor and manna have no workflow.
  - Server image, k3s rollout, mobile lanes and knowledge-graph loads run outside CI with no receipts.
- **Stale:**
  - `bestbuds/AGENTS.md` §10 describes the deleted per-repo `.dagger` module and its functions (`pr`, `app-checks`, `deploy-worker`).
  - `bestbuds/.dagger` still holds 59 MB of SDK, and `package.json` still points `cloud.dagger` at it.
  - `DaggerToolProvider` hard-codes nine functions that no longer exist.

### 3.5 Consumers and docs

- **Catalogs:** three operation catalogs.
  - reaktor-cloud's `CloudToolProvider` family is live only in e2e tests.
  - `BestBudsOperationalCatalog.kt` is 2,334 lines, with its own rollout and k3s entries.
  - The dependeasy model.
- **Safety by substring:** safety is classified by matching strings such as `"pulumi up"` and `"wrangler deploy"` in `CloudToolCatalogProvider`, `JvmProjectDiscovery` and `DaggerToolProvider`.
- **Duplicated logic in the pane:** the Hangar Deploy pane recomputes deploy order (`BestBudsSystemTopology.kt:317`) instead of reading the fleet's edges. It finds workers by scanning `targets/*/wrangler.json`.
- **Stale docs:**
  - `DEPLOY.md` describes `predeploy`/`jq`, a missing `wrangler.prod.json` and "no migration runner". It lists 12 of the 20 workers and has no server or observability section.
  - `AGENTS.md` §9 names `./deploy.sh`, `deployApp` and `deployReaktor`, none of which exist.
  - `k3s/README.md` still advises Docker Desktop, which was removed on 6 Oct.
  - `reaktor-cloud/README.md` describes the old module.

## 4. The target design

### 4.1 Two kinds of change, one contract

- **Infrastructure** changes slowly. It covers Grafana folders, dashboards and alerts, Helm releases, namespaces, and Cloudflare D1, R2, Hyperdrive and routes. Pulumi in Kotlin owns it. A Pulumi preview is its plan.
- **Releases** change often. They cover a worker version, a server image, a store build, a migration and a secret set. Typed dependeasy deploy kernels own them.

Both are model nodes, and both implement one contract:

| Verb | Meaning | Examples |
|---|---|---|
| plan | what would change, read-only | `wrangler deploy --dry-run` plus a config diff against the live worker; image digest live versus wanted; `pulumi preview`; pending migrations |
| apply | make the change | deploy, rollout, `pulumi up`, migrate |
| verify | check it took | health probe, rollout status, `pulumi preview` shows no changes, migration ledger head |
| rollback | return to the previous receipt's version | previous Cloudflare version, previous digest, previous Pulumi update |

Every apply leaves a **deploy receipt** in the existing receipts store. It records:
- the node and environment;
- the git sha and runner (local Mac, m1-worker, Dagger, CI);
- the plan digest;
- the artifact digest;
- the remote version: Cloudflare version id, image digest, Pulumi update id or migration ledger head;
- the rollback target;
- the result.

The Cloudflare `--message` carries the run id, so live state and the receipt can be joined.

### 4.2 One product delivery declaration

Each product declares its delivery facts once, in Kotlin, in its build logic: environments, accounts, zones, registry, cluster, workloads, databases and the Grafana stack. Everything else is generated from it or reads it:
- the final Wrangler configs for both environments;
- the secrets lists;
- the karate maps;
- the exporter scope;
- the pnpm workspace entries;
- the Kubernetes manifests' variable parts;
- the Pulumi stack config;
- the Hangar topology.

The shape (names illustrative):

```kotlin
object BestBudsDelivery : Delivery {
    val Prod = environment(Environments.Prod) {
        cloudflare(CloudflareAccounts.BestBuds, zone = Zones.BestBudsAi)
        gcp(GcpProjects.Mehmaan, Regions.AsiaSouth1, registry = Registries.Docker)
        cluster(Clusters.ReaktorVm, namespace = Namespaces.Reaktor)
        grafana(GrafanaStacks.Reaktor, folder = GrafanaFolders.BestBuds)
    }
    val Dev = environment(Environments.Dev) { inherits(Prod); cloudflare(CloudflareAccounts.BestBuds, workersDev = true) }
}
```

And in the root `workspace {}`:

```kotlin
deploy("workers") { after(pr); fleet(projects.workers) }
deploy("server") { after(pr); rollout(projects.reaktorServer, Workloads.AppServer) }
deploy("observability") { stack(projects.observability) }
migrate("postgres") { database(BestBudsDatabases.Main) }
secrets("workerSecrets") { fleet(projects.workers) }
```

### 4.3 Dagger only runs nodes

- **Arguments:** the module takes a node id and an environment and runs that node in the toolchain container.
- **Host and secret checks:** the Kotlin runner decides which nodes can run in a container and which secrets each node needs, from the model.
- **`pipeline.json`:** it shrinks to the engine version and the toolchain contract. The contract now includes the apt packages, cache volumes, memory policy and browser image.
- **The target list goes.** `pipeline.json` changes only when the toolchain changes, and `verifyDagger` loses its reason to exist.
- **Output:** `run` returns a directory with the run's receipts and the node's declared outputs. CI uploads it and Hangar's run ledger ingests it.
- **CI:** the gate runs locally through the shared runner; the owner's local-only decision in §6 supersedes reusable GitHub workflows and per-repository callers.

## 5. Work packages

Order: WP19 can start now and runs alongside WP11–WP18. WP20 onwards needs WP12 (typed node receivers with `DeploySpec`, `WorkerRef` and `Environment`) and WP13 (the worker fleet and the server as component projects). WP21 proves the whole path once, for Cloudflare, before the others follow the same contract.

### WP19 — Fix what is broken, delete what is dead

Goal: every deploy path that exists today works and is described correctly.

Steps:
1. **Workers:**
   - declare `app-service` as an external binding in the fleet, so the order edges keep it;
   - make a dev fleet deploy skip the prod-only workers explicitly instead of failing partway;
   - point the three `wrangler dev --remote` workers at their dev configs;
   - check the Deploy pane's worker matching against generated scripts and fix it if it matches nothing.
2. **Server:**
   - regenerate or retire `release.json` so the reviewed rollout's precondition matches the live digest;
   - fix the `secrets.yaml` bootstrap gap between `vm-setup.sh` and `cloud/k3s/deploy.sh`;
   - pin `cloudflared` and `node-exporter` by digest.
3. **Pulumi:** `PulumiToolProvider` uses the pinned CLI from dependeasy's toolchain, not PATH.
4. **Delete:**
   - `bestbuds/.dagger` (59 MB) and manna's empty `.dagger`, plus `package.json`'s `cloud.dagger` pointer;
   - `DaggerToolProvider`'s nine dead functions;
   - `ReaktorBrowser` and `cloudDefinitionDigest`, which have no callers;
   - the "Mirrors index.ts" remnants.
5. **Rewrite the stale docs:**
   - `DEPLOY.md` (workers, server, observability);
   - `bestbuds/AGENTS.md` §9 and §10;
   - `k3s/README.md` and `reaktor-cloud/README.md`.

   Each states the current commands only.

Acceptance:
- A dev fleet deploy completes.
- The reviewed rollout's precondition passes against live, read-only.
- Every command in the rewritten docs exists.

Budget: no new API.

### WP20 — One product delivery declaration

Goal: each delivery fact has one home per product (§4.2).

Steps:
1. Add the `Delivery` declaration type and `Environment` handles to dependeasy.
2. Declare the facts in the product build logic for bestbuds and manna.
3. Move `fleet.json` into `contracts/` and extend it with bindings, routes, vars, secret names and migration folders.
4. Generate both environments' final Wrangler configs by the same merge. Remove the copied root defaults from the sources, and give every fleet worker a dev entry or an explicit "prod only".
5. Generate from the fleet contract, or read from it:
   - the secrets lists;
   - the karate maps;
   - `pnpm-workspace.yaml`;
   - `surface-coverage.spec.mjs`;
   - the exporter scope;
   - `BestBudsSystemTopology`;
   - the four Kotlin app maps.

   Delete the hand-written copies.
6. Move manna onto `workers {}` for `api` and `appWeb`.
7. Accept one environment input, the typed option, everywhere. Delete `TARGET_ENV` and the literal `"dev"`.

Acceptance:
- In both products, the fleet contract is the only worker list. Report the 11 hand-written lists going to 0.
- Final configs for dev and prod come from one merge.
- manna has a fleet contract.

### WP21 — One deploy contract, Cloudflare first

Goal: one worker deploy goes from declaration to plan, apply, receipt and Hangar, end to end.

Steps:
1. Add the deploy kernel contract (§4.1): plan, apply, verify and rollback, plus the deploy receipt in the existing receipts store.
2. Implement it for workers:
   - **plan:** `--dry-run` plus a diff of the final config against the live worker's settings;
   - **apply:** with `--message <run id>`;
   - **receipt:** records the Cloudflare version id;
   - **verify:** a health probe declared on the worker;
   - **rollback:** to the previous receipt's version.
3. Keep one alias per deploy node. Delete the raw `xWorkerDeploy` path, so a deploy always runs its prerequisites.
4. `reaktor build plan <node>` shows the deploy plan for deploy nodes, and `reaktor build show <node>` shows the latest receipts.

Acceptance:
- A real dev deploy of one worker shows its plan first, then a receipt with its Cloudflare version id.
- Hangar's Deploy pane shows that receipt joined to the live version.
- A rollback to the previous receipt works on dev.

### WP22 — Dagger as a thin container host; local CI

Goal: §4.3 holds, with TypeScript as glue only.

Steps:
1. Move these out of `index.ts` into the Kotlin toolchain contract:
   - the apt packages;
   - the cache volume names;
   - the memory policy (structured, not a `GRADLE_OPTS` string);
   - the browser image;
   - the wrapper rewrite. Better still, point the container at the Gradle archive through a supported property instead of `sed`.
2. Drop the target list from `pipeline.json`. `run(node, environment)` resolves the node inside the container from the model. The Kotlin runner refuses nodes that need a Mac or a phone, with a clear message.
3. Declare secrets per node in the model and pass them by name. Delete the "Deploy means Cloudflare token" rule.
4. Make `run` return receipts and declared outputs as a directory. The local CI gate retains it and the run ledger ingests it.
5. Delete karate's per-environment duplicate nodes: the environment becomes a parameter, as for deploys.
6. Keep one local CI gate callable from all three repos. Do not add GitHub Actions workflows; decision 2 records the owner’s local-only CI direction.

Acceptance:
- Adding a node does not change `pipeline.json`.
- `index.ts` contains no package lists, shell snippets or product knowledge. Report its line count before and after.
- A local CI run's receipts appear in Hangar.

### WP23 — Server releases by image digest

Goal: one path changes `app-server`, and it is a node.

Steps:
1. Image build is a node whose output is a digest, not a tag. A push node sends it to the registry named in the delivery declaration.
2. A rollout kernel in Kotlin replaces `rollout-reviewed-image.py`:
   - **plan:** live digest versus wanted;
   - **apply:** compare-and-swap with the declared surge policy;
   - **verify:** rollout status;
   - **rollback:** to the previous receipt's digest.

   The release contract is generated, not hand-written.
3. Fold the JVM OpenTelemetry patch into the generated manifests.
4. Delete `reaktorServer/deploy.sh`, the README recipe and `observability/deploy.sh jvm`. Remove `:latest` from `springImage`.
5. One kubeconfig resolution, from the delivery declaration and `BuildOption`.

Acceptance:
- A real rollout to prod goes through plan, apply, verify and a receipt carrying the digest. This needs the owner's go, because it is production.
- No other path can change `app-server`. Report the four paths going to one.

### WP24 — Data changes as nodes: secrets and migrations

Goal: secrets and schema changes are planned, applied and recorded like deploys.

Steps:
1. **Secrets nodes:**
   - the names come from the fleet contract and the values from `bestbuds/config`, which stays tracked;
   - **plan:** lists missing and extra names, never values;
   - **apply:** puts them through Wrangler.

   Port the logic of the two secrets scripts into the kernel and delete the scripts.
2. **Postgres migration node:** port `postgres-migrate.mjs` (ledger, drift check, apply) to Kotlin.
   - **plan:** pending migrations;
   - **receipt:** the ledger head.
3. **D1 migration nodes:** cover the three bestbuds folders and manna's, with a remote apply.
4. **ClickHouse SQL apply node:** for `cloud/k3s/clickhouse`.
5. Each deploy node that needs a migration declares it, so the order is in the model.

Acceptance:
- The model has secrets and migration nodes for both products, each with kernel, spec and declaration site.
- On dev, each plan runs read-only and reports pending items.
- No secret value appears in any plan, receipt or log.

### WP25 — One infrastructure-as-code path: Pulumi in Kotlin

Goal: observability has one source, and Pulumi runs as a model node.

Steps:
1. Add a dependeasy Pulumi kernel using the pinned CLI:
   - **plan:** `preview`;
   - **apply:** `up`;
   - **receipt:** the update id.

   The stack config is generated from the delivery declaration. Secrets come by name, as in WP24.
2. Port the live system into the Kotlin program:
   - the Alloy collector;
   - the 17 alerts;
   - the 8 dashboards, keeping their existing UIDs and the `reaktor-bestbuds` folder;
   - the Cloudflare metrics path, with one chosen between the exporter and the Infinity datasource.

   Import the existing resources so nothing is recreated. Delete the unused `k8s-monitoring` release from the program.
3. Move the chart versions into `ToolchainVersions`. Do placeholder substitution through typed values, not string replacement inside resource functions.
4. Delete `sync-grafana.py`, `observability/deploy.sh` and the duplicate dashboard copies.
5. Per decision 1, Cloudflare D1, R2, Hyperdrive and routes, and the cluster's base resources, can follow into the same program later.

Acceptance:
- After the import, `pulumi preview` on the prod stack reports no changes.
- The live alerts and dashboards are unchanged.
- No Python or shell path writes to Grafana.

### WP26 — Consumers read only the model and receipts

Goal: Hangar, the CLI and MCP answer "what can be deployed, what is live, what changed" from one source. This extends WP18 step 5 to delivery.

Steps:
1. Hangar's Deploy pane reads deploy nodes, their plans, their receipts and the live state joined by version id or digest. It no longer recomputes order or scans Wrangler files.
2. Delete reaktor-cloud's `CloudToolProvider` family, or rebuild it as a projection of model nodes. `BestBudsOperationalCatalog`'s deploy, k3s and rollout entries become model projections.
3. Remove the substring safety classifiers. Safety comes from the node's effect and environment.
4. MCP gets deploy plan and receipt reads. Applying stays owner-approved, as today.

Acceptance:
- No consumer finds deploy targets by file scanning, script prefixes or regex. Report the classifier count going to 0.
- Hangar shows the same plan and receipt as the CLI for one worker, the server and the observability stack.

## 6. Owner decisions (approved 14 Oct)

The owner approved all five defaults on 14 October 2026.

1. **Pulumi's scope.**
   - Default: Pulumi owns observability now, and later the long-lived cloud resources (Cloudflare D1, R2, Hyperdrive, routes and the cluster's base resources).
   - Releases (workers, image rollouts, migrations, secrets) stay typed deploy kernels.
   - The alternative is to drop Pulumi and keep the scripts. It is not recommended because it contradicts the Kotlin-everywhere rule.
2. **CI execution.** Superseded by the owner’s explicit instruction: “we don’t need github actions. even drop the ones in bestbuds and record it. CI should run locally.” Keep one local gate through `reaktor build run pr`; remove the existing BestBuds workflow and add no GitHub Actions workflows or callers. WP11 has already removed that workflow.
3. **Secret values.** Default: values stay in the tracked `bestbuds/config` (and manna's equivalent). Nodes refer to them by name, and plans and receipts never contain values.
4. **Images.** Default: releases are by digest only, and `:latest` goes.
5. **Production steps.** Default: the WP21 rollback test and the WP23 prod rollout run only with the owner's go at that moment. Everything else qualifies on dev first.

## Log

- 21 Oct 2026 — WP19 live acceptance completed after the owner's explicit approval to deploy the 16 dev workers. The graph-owned fleet selected exactly the approved 16 and excluded all five production-only workers. All deployments succeeded; read-only Cloudflare deployment observations confirm every new version is active at 100%. The retained Gradle receipt incorrectly carries the preceding dry run's cached flag; it is preserved unchanged with the provider observations. The current-invocation bridge fixes that metadata bug and all five existing receipt/bridge cases pass, including a regression alternating dry/live/dry with cache reuse. No redeployment was needed. Evidence: `wp19-dev-live-20261021-v1-proof.json` and `wp19-receipt-dry-run-20261021-v2-proof.json` under the qualification receipts store. This closes WP19; WP21's typed deploy receipt, health probe and rollback remain separate acceptance.

- 21 Oct 2026 — WP20 Manna fleet qualification completed and pushed (Reaktor `4f7c0bb7`, Manna `0d96748`). One fleet owns API/web deployments with typed API verification and browser bundle producer edges; obsolete standalone publish/deploy APIs are removed. Nine existing fixtures pass; both final configs match their pre-migration contents semantically, and actual generation/graph runs reuse the configuration cache. Six copied common defaults are removed. Manna has only its declared production environment; no Manna provider write occurred. Evidence: `wp20-manna-fleet-20261021-v5-proof.json`. The rest of WP20 remains open.

- 21 Oct 2026 — WP19 runtime Pulumi qualification: the existing provider requires the graph-owned `InstallTool` spec, rejects absent/mismatched installation receipts before starting a process, and plans the exact binary path. SDK generation now records its `pulumiSetup` prerequisite in the canonical graph. Both existing SDK boundary fixtures and the existing cloud process-boundary executable pass; the actual installed binary reports `v3.268.0`. No provider write or live Pulumi adoption is claimed. The current server's read-only reviewed-rollout checks pass (manifest/live digest, capacity policy and ready-pod attestation); the obsolete one-shot release remains retired. Evidence: `wp19-runtime-pulumi-20261021-v2-proof.json` and `wp19-rollout-readonly-20261021-v2-proof.json` in the qualification receipts store. Real dev bundles and live fleet acceptance remain open.

- 21 Oct 2026 — WP19 dev fleet graph qualifies: all 8 existing web contract cases pass; BestBuds selects 16 dev workers and excludes 5 production-only workers. Dev binding edges use dev services. Actual aggregate dry runs pass cold/warm with configuration-cache reuse; production restores the same 1,625 canonical nodes and 1,609 edges. No Cloudflare write was performed. The read-only server deployment has one ready replica using the manifest's pinned digest; the obsolete reviewed release stays retired. Runtime Pulumi pinning and live dev acceptance remain open. Evidence: `build/dependeasy/receipts/qualification/wp13/wp19-dev-fleet-20261021-v4-proof.json` and `wp19-rollout-readonly-20261021-v1.json`.

- 21 Oct 2026 — WP19 dev-fleet follow-up budget before source edits: extend the existing fleet adapter and package/fleet fixture to resolve the declared environment once, filter the aggregate to supported workers and derive binding edges from that environment. No new API, flag, state, dependency or scheduler. Preserve the default production graph; prove dev exclusion, dev bindings, unknown-environment refusal and configuration-cache reuse, then inspect the real BestBuds dev graph. Live deployment follows its reviewable plan. Budget: `build/dependeasy/receipts/qualification/wp13/wp19-dev-fleet-20261021-v1-budget.json`. Initial fixture compilation exposed two missing imports; corrected without production behavior changes. Local CI wording in §4.3 now matches the owner's recorded decision.

- 14 Oct 2026 — Review written from the Dagger/CI reading plus two read-only audits (Pulumi/k3s/observability, Cloudflare/consumers). Nothing was changed, run against a cloud, published or committed.
- 14 Oct 2026 — The owner approved all five §6 decisions.

- 15 Oct 2026 — API handoff reconciled: `dependeasy-api.md` is authoritative for WP12/WP13/WP16; new kernels receive no unit-test suites, and Gradle-free execution is proved by direct CLI invocation. The five delivery decisions concern infrastructure scope, CI, secrets, digest releases and production approval; they do not authorize inclusion of the pending two-file listener patch. Decision 2 and WP22 now record the owner’s later local-only CI direction.

- WP19 budget before edits, first independent cleanup slice: remove the obsolete nine-function DaggerToolProvider (including its unused digest helper), unused ReaktorBrowser and the obsolete Dagger-only E2E case/imports; remove the stale Mirrors-index.ts comment and abandoned BestBuds/Manna .dagger directories. Rewrite reaktor-cloud README against current Gradle/KMP tasks and distinguish existing Pulumi source from the live observability path. No new concepts, public API, flags, state, dependencies, tool pins or kernel tests. Acceptance: repository-wide Kotlin caller scan; the existing cloudToolE2e executable still verifies one process owner plus Pulumi approval/source-drift admission; actual routed Gradle compile/run; source diff check. Remaining WP19 deploy/server/Pulumi-pin work stays open. Keep this slice uncommitted until the WP11 shipping boundary is resolved.

- WP19 server slice budget before edits: the read-only live deployment image differs from all three fields in the old one-shot release.json. Retire that stale manifest instead of rearming an old artifact against today’s image; retain its bytes and the live comparison under qualification/wp19. Keep the reviewed rollout recipe and all compare-and-swap/readiness guards; give missing reviewed input an explicit error. Bootstrap must require its ignored manifests/secrets.yaml before the first bucket/disk/firewall/VM mutation; the existing rsync already carries that path when present. Adjust only the existing deploy-safety fixture to run a private script copy with placeholder manifest input. No new API, options, secret values, dependencies, release or cloud write. Acceptance: existing deployment/rollout checks, missing-input black-box refusal, shell syntax and read-only live digest evidence. Rewrite the affected k3s README sections to describe current bootstrap and digest review, without replaying an obsolete release.

- WP19 remote-dev and image-pin slice budget before edits: point only textModeratorServer, imageModeratorServer and verificationServer dev scripts at their existing standalone wrangler.dev.json files. Use the current pinned Wrangler CLI to check flag support and parse/bundle those dev configs without upload. Pin cloudflared and node-exporter manifests to the exact digests attested by their currently Ready production pods; this changes source only and performs no cluster apply or version upgrade. No new API, dependencies, options or kernel tests. Acceptance: all three dev configurations resolve distinct -dev worker names, local config checks pass, manifest syntax/digests match retained live reads, existing server checks remain green. Current Cloudflare commands/best-practice references and installed CLI are the retrieval sources; preserve repository pins.

- WP19 documentation/pointer budget before edits: remove only package.json reaktor.cloud.dagger, now pointing at the deleted legacy module; rewrite BestBuds DEPLOY.md and AGENTS.md sections 9/10 around the existing generated Gradle/package aliases, shared Dagger runner, local-only CI, reviewed digest rollout and current k3s observability scripts. Preserve the existing unrelated AGENTS.md change and its MCP-access subsection exactly. No source API, deployment, generated aliases, dependencies, pins or tests change. Acceptance: every named package alias, Gradle target, file and shared Dagger option resolves in the current source; JSON and shell syntax pass; the owned documentation patch excludes the foreign AGENTS.md line. State remaining fleet, Deploy-pane and runtime Pulumi gaps explicitly instead of claiming WP19 complete.

- 15 Oct 2026 — WP19 independent cleanup is prepared and remains uncommitted. Removed the unused Dagger provider/digest/browser and abandoned .dagger directories/pointer; retained current shared Dagger execution. Routed cloud compile plus the existing cloudToolE2e passes (58.946s); ten existing server checks pass. The actual bootstrap script refuses absent secrets after one inventory read and before any mutation. Three existing dev Worker configs bundle locally with Wrangler 4.148.0, each selecting its -dev service; no upload. The old server release manifest is retired after its precondition disagreed with the live read, with original bytes/evidence retained. Both source image pins match previously Ready pod imageIDs. YAML parse/digest checks pass; current kubectl API discovery failed with a TLS internal error, so no Kubernetes schema/live acceptance is claimed. DEPLOY.md, AGENTS.md sections 9/10, k3s README and reaktor-cloud README now describe current local CI, generated aliases and observability ownership. The foreign AGENTS.md line and MCP-access subsection are preserved; its owned patch contains only sections 9/10. Durably retained source hashes, owned patches and checks are in build/dependeasy/receipts/qualification/wp19/cleanup-candidate.json. Removed 24,342,199 bytes of owned dry-run bundles after hashing.

- WP19 remains open for an explicit external app-service binding/order edge, skipping prod-only workers in a dev fleet aggregate, model-based matching in Hangar's Deploy pane, and routing runtime Pulumi through its pinned toolchain. No whole-fleet apply or fresh reviewed server rollout was performed. The earlier WP11 native/container/IntelliJ qualification covers its exact recorded candidate only, not this WP19 overlay. The listener proposal's two hashes remain unchanged; inclusion authorization under dependeasy-handoff.md §10.7 is still pending. No new commit, push or PR; WP12 implementation has not started.

- 15 Oct 2026 — New kernel handoff incorporated before further implementation: WP11 remains the shipping gate; K0 follows its commit before WP12, per kernel-handoff.md D2. WP12/WP13 target the product/verify/delivery story in kernel-handoff.md §3 and dependeasy-api.md §4.4; retain generated package aliases until each typed CLI replacement exists. WP19 may continue independently.

- WP19 Deploy matching budget before edits: replace only the Worker command-text lookup with the existing fresh BuildModelReader model, OperationalTask.buildNodeId and node.workerDirectory, joined to the repository resource's workspace attribute. Keep the existing workload matching and approval/run path. Update the existing desktop deploy fixture to carry its actual declared build-node identity and generated Gradle summary. No new public API, schema, flags, state, dependencies or kernel unit tests. Acceptance: the existing F-CLOUD-15-deploy-a-worker Maestro flow reaches the plan and completes its fixture run; the repository fixture exposes a deploy action for each declared Worker, including standalone dev variants, without parsing command text; stale models do not authorize a deploy. Qualify only the owned overlay in the existing scratch checkout, preserving primary foreign changes.

- WP19 Deploy matching acceptance refinement before edits: extend the existing F-CLOUD-15-deploy-view Maestro flow to require the analytics Worker action from the real repository catalog and no action for the externally referenced app-service. Keep the separate approved fixture-run flow. The existing deploy fixture may be loaded for an empty test workspace; attach its build-node identity only when that workspace has a model. Missing/stale production models remain unavailable through the existing CloudAssembly boundary. No new flow, API or kernel test.

- WP19 Deploy matching acceptance correction before edits: the repository UI fixture deliberately uses DesktopE2EControlPlaneFixture's bounded task list, not BestBudsOperationalCatalog. The added analytics action assertion therefore failed against a catalog without that task. Remove those two added view assertions; retain the existing view flow and typed messaging approval/run flow. Validate all declared production/dev Worker mappings and external app-service exclusion by invoking the compiled matcher with the production catalog and repository declarations directly. Preserve the failed attempt as evidence. No new API or kernel test.

- 15 Oct 2026 — WP19 Worker action matching is qualified as a separate uncommitted slice. Hangar joins OperationalTask.buildNodeId through the fresh model's current-root nodes and workerDirectory to repository workspace attributes; the Worker command-summary regex is removed. A direct invocation of the compiled production catalog, declarations and matcher covers all 36 production/dev variants of the 20 declared deploy targets. The browser Worker config has no deploy node and receives no action; external app-service also receives none. Fresh model works; stale and missing models are refused. Both existing F-CLOUD-15 Maestro flows pass in 47.795s, including the typed messaging plan, approval, fixture execution and run history. The first reused-daemon compile hit Metaspace; a fresh daemon passed with unchanged limits. The incorrectly broadened view assertion and probe assumptions are retained in the failed-attempt evidence. No new API, state, flags, dependencies, kernel tests, live writes or commits. Exact source hashes, owned patch and evidence: build/dependeasy/receipts/qualification/wp19/deploy-matching-candidate.json. External service ordering, dev fleet filtering, pinned runtime Pulumi and fleet/live acceptance remain open.
