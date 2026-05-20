# Reaktor Shadow: Testing & Quality Infrastructure

Testing in Reaktor is infrastructure, not an afterthought. **Reaktor Shadow** captures and replays traffic at the Service layer (across all six transports) using FlexBuffer messages. This unified capture mechanism covers HTTP, LOCAL, PEER, PUBSUB, QUEUE, and WORKFLOW.

## Tooling & Integration

| Tool | Purpose | Integration |
| --- | --- | --- |
| **Maestro** | Mobile UI testing: record/replay user flows via declarative YAML. | The Blueprint editor records journeys and exports them as Maestro flows. CI runs these against every build. |
| **Keploy** | API testing via traffic capture. Auto-generates test suites. | Shadow captures Service layer traffic and generates Keploy test cases for regression detection per PR. |
| **Device Lab** | Firebase Test Lab / BrowserStack for cross-device validation. | CI runs Maestro flows on a device matrix, with results displayed in the Blueprint editor. |
| **Load Testing** | k6 / Gatling scripts generated from captured production traffic. | Shadow captures real volume and timing patterns to generate realistic load test scripts. |
| **Flamegraph Profiling** | `async-profiler` (JVM), `perf` (native), and `CDP` (JS). | The Blueprint editor overlays flamegraph data directly on graph nodes for hot-path visualization. |
| **Web/App Vitals** | Core Web Vitals (web) and startup time/frame rate (mobile). | Telemetry is collected per `RouteNode`, and the Blueprint editor shows per-screen performance grades. |

## The "Shadow" Advantage

Because Reaktor captures traffic at the **Service layer**, it can replay complex multi-step interactions without requiring a live production database. By replaying FlexBuffer streams into a local graph, developers can reproduce production bugs with 100% fidelity, even those involving complex P2P mesh networking or distributed actor states.
