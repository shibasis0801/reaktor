# Priority Checklist: 10 Things to Do Now

Ranked by impact-to-effort ratio, these tasks are critical for Reaktor's transition from a framework to a dominant application engine.

## Phase 1 Readiness

| Priority | Action | Rationale | Est. Effort |
| --- | --- | --- | --- |
| **01** | **Publish Engine Modules** | Nobody can use Reaktor externally until modules are on Maven Central. | 1 Week |
| **02** | **Launch Documentation** | This site. Target: Zero to running app in 15 minutes. | 3 Days |
| **03** | **Automated CI Suite** | Every commit without tests is technical debt. Setup GitHub Actions. | 3 Days |
| **04** | **Build Reaktor CLI** | First impression is everything. Scaffold projects with a single command. | 1 Week |
| **05** | **Standalone Visualizer** | Reaktor's "wow" moment. Render graphs via React Flow. | 2 Weeks |
| **06** | **Archive Brainstorming** | Merge or archive unfinished modules to signal project focus. | 1 Day |
| **07** | **Reaktor.dev Landing** | A GitHub README is not a product. We need a high-impact vision URL. | 3 Days |
| **08** | **15-Minute Demo Video** | Highest bandwidth medium to convey "why this matters." | 2 Days |
| **09** | **Schema DSL Spec** | The keystone of DX. Define syntax and evolution rules formally. | 1 Week |
| **10** | **Community Infrastructure**| Discord and GitHub Discussions must exist before users arrive. | 1 Hour |

## Implementation Strategy

We follow the **Unreal Engine Playbook**: build a flagship product (*Bestbuds*) to prove the engine, while simultaneously lowering the barrier for external developers via CLI and Schema DSLs.
