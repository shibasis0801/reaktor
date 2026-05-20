# Application Patterns & Adoption

Reaktor can be adopted in two primary ways: starting a new (greenfield) project or integrating it into an existing (brownfield) system.

## Greenfield Application Patterns

The following patterns are easily scaffoldable via the `reaktor-cli`:

| Application Type | Reaktor Modules | Key Benefit |
| --- | --- | --- |
| **Social / Community** | Graph + Auth + DB + Mesh + Actors + UI | Offline-first, real-time P2P with graph-based recommendations. |
| **E-Commerce** | Graph + Auth + DB + Service + Workers | Durable Object inventory coordination and multi-region typed contracts. |
| **EdTech / Learning** | Graph + Auth + DB + AI Nodes + Actors | Knowledge graphs, AI learning paths, and collaborative study. |
| **IoT Dashboard** | Graph + Mesh + Actors + Telemetry + C++ | Sensor actors, SIMD signal processing, and edge inference. |
| **Multiplayer Game** | Graph + Mesh + Actors + C++ | P2P WebRTC, actor game state, and SIMD physics. |
| **Enterprise Tool** | Graph + Auth + DB + Service + RN bridge | Typed RPC, RBAC auth, and offline field applications. |
| **AI-Powered SaaS** | Graph + Auth + AI Nodes + Agents | Agents as graph nodes with caching, fallbacks, and usage tracking. |

## Brownfield Adoption for Existing Systems

For organizations with mature codebases (e.g., Myntra or Flipkart), a phased adoption strategy is recommended:

-   **Months 1-2: Feature Integration**: Integrate Reaktor into a single feature. The service layer handles offline caching and typed API contracts while the existing backend remains unchanged.
-   **Months 3-4: Introspection**: Add telemetry and the Blueprint editor to visualize the system as a live graph, helping convert skeptics within the organization.
-   **Months 5-6: Proxying**: Use `ServiceNode` proxies to connect Reaktor to existing backend services. The backend remains untouched, while frontend code begins to leverage Reaktor's benefits.
-   **Months 7-12: Full Platform Adoption**: Gradually transition to using the Reaktor Mesh for real-time features, Durable Objects for edge caching, and AI agents.

### Key Selling Point for Enterprise
Reaktor doesn't replace existing systems; it wraps them in a typed, observable, and deployable graph. For example, Spring Boot services can become graph nodes via `ServiceNode` proxies, making the entire system visible and composable without throwing away any existing investments.
