# Shared agent workspace qualification — 13 September 2026

The shared service now exposes Single, Compare and Council through the same local owner used by desktop and MCP. The [published design](https://reaktor.build/docs/reaktor-agent-workspace) contains the research, six Pencil boards, current limits and next delivery gates.

## Verified

- Conductor suite: 59 tests, 58 passed, one existing opt-in test skipped.
- Desktop targeted suite: three tests passed, including two state tests and rendering all three task views at 1024×720 and 1280×900.
- Workspace tests cover five-turn council accounting, independent model choices, prompt visibility, completed stage persistence, unknown usage, idempotent retry, changed-request rejection, capacity reservation, cancellation of both participants, failure despite successful synthesis, no reuse of ephemeral council sessions, and interrupted-participant recovery without redispatch.
- A live Compare request ran against installed Codex 0.154.0 and Claude Code 2.1.270 using the operator's configured models and authentication. Both completed. This verifies actual native comparison wiring; a live five-turn council was separately qualified in the earlier tabs experiment.
- Retrying that exact live request after restarting its owner returned the same receipt and native session identifiers without additional model turns.
- Public and signed-in documentation builds completed. The new public plan returned HTTP 200 after deployment.

The live smoke prompt asked for a short tab-ownership invariant and explicitly requested no file reads, edits or tools. It ran in a dedicated empty workspace. The responses are smoke evidence, not accepted architectural decisions. Private run records and transcript are retained under `reaktor-context/agent-workspace-revamp-2026-09-13/` outside these repositories.

## Reported live comparison usage

| Metric | Reported |
| --- | ---: |
| Provider turns | 2 |
| Inclusive input | 35,368 |
| Cached input | 21,958 |
| Fresh input | 13,410 |
| Cache-write input, a subset of input | 7,898 |
| Output | 310 |
| Reasoning output, a subset of output | 213 |

These are provider-reported counts, not a billing total or a savings measurement. Even a very small request pays native setup/context overhead; eliminating repeated discovery and unnecessary council rounds remains a meaningful optimization target.

## Limits retained

Collaborative runs request inspection; provider shell, hooks and MCP effects are not completely mediated by Reaktor. Prompt-level independence does not isolate shared files or memory. The existing conductor can continue later rounds after a failure; the terminal receipt remains failed. Selective stage retry, live steering, provider questions/approvals, worktree ownership, automatic selected-graph context and the full proposed desktop workspace remain planned.

The one-time smoke script initially failed while printing an omitted optional `failure` field after both providers had already completed. Reading the saved receipt verified completion; correcting that report and retrying the same idempotency key returned the saved result. This was a reporting issue, not a failed provider run.
