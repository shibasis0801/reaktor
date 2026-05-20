# The Five-Runtime Graph Model

A Reaktor application is not a single runtime. It is a distributed system spanning five distinct execution environments, all modeled as graphs and visible in the Blueprint editor.

## The Five Runtimes

| Runtime | Execution Environment | Update Path | Latency |
| --- | --- | --- | --- |
| **Common/KMP** | Native mobile binaries (Android/iOS) and JVM server JARs. | Native release via App Store/Play Store. | Days to weeks. |
| **JVM Server** | Spring Boot on k3s (GCP VM). | Container build and k3s rolling update. | 30s to 5 min. |
| **JS Worker** | Cloudflare Workers for Platforms. | JS bundle upload to Cloudflare API. | &lt; 10 seconds. |
| **JS Web** | Browser runtime. | JS bundle upload to CDN (Pages or R2). | &lt; 30 seconds. |
| **JS Mobile** | Hermes JS engine embedded in the native mobile app. | JS bundle upload to Cloudflare R2. | Minutes to hours. |

## Graph Coloring and Partitioning

In the Blueprint editor, every node is visually tagged with its runtime target using a color system that communicates how it can be updated:

-   **Common nodes (gray/white)**: Exist on all targets. These are the slowest to update but the most portable.
-   **JVM-only nodes (deep blue)**: Server-side only. Fast deployment via k3s.
-   **JS Worker nodes (orange)**: Edge-only. Near-instant deployment via Cloudflare.
-   **JS Web nodes (cyan)**: Browser-only. Instant deployment via CDN.
-   **JS Hermes Mobile nodes (green)**: Hot-updatable mobile logic. Deployed via R2 bundle download without an app store review.

## Proxy Nodes: ServiceNode and NativeNode

When graphs cross runtime or language boundaries, two specialized proxy node types handle the translation and infrastructure:

### ServiceNode (Remote Edge Proxy)
Handles communication between graphs on different peers or runtimes. When a Kotlin graph node on mobile calls a JVM server graph node, a `ServiceNode` is inserted at the boundary. It handles serialization (FlexBuffer), transport (HTTP, Mesh, etc.), retries, and circuit breaking.

### NativeNode (Bridge Edge Proxy)
Handles communication between graphs in different languages on the same device. For example, when a React Native (JS) graph node calls a Kotlin (native) graph node, a `NativeNode` is inserted at the FFI boundary. It serializes to FlexBuffer, calls through the C++ ABI surface, and manages memory ownership.
