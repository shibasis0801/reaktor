# Reaktor Mesh: Universal Connectivity Architecture

Reaktor Mesh is a transport layer that makes the application graph truly distributed. It operates on the core insight that the distinction between "server" and "client" is artificial at the transport layer—every node is a peer with different capabilities and lifetimes.

## Peer Classification

Peers exist on a spectrum defined by addressability, lifetime, and network position:

| Peer Type | Addressable | Lifetime | Transport |
| --- | --- | --- | --- |
| **GCP Server (k3s)**| Yes | Long | WebRTC direct. |
| **Cloudflare Worker**| Yes (HTTP only)| Ephemeral | WebSocket relay. |
| **Durable Object** | Yes (via DO API)| Long-ish | WebSocket relay. |
| **Mobile Client** | No | Short | WebRTC + TURN. |
| **Desktop Client** | No | Medium | WebRTC direct (most cases).|
| **IoT / Raspberry Pi**| No | Long | WebRTC + TURN. |

## The Three Planes

### 1. Discovery Plane: "Who exists and where?"
A global peer registry lives across Durable Objects, sharded by namespace. Each Durable Object instance maintains a consistent view of peers in its namespace, providing single-digit millisecond responses from the edge.

### 2. Signaling Plane: "How do I establish a connection?"
Signaling is always routed through infrastructure (never P2P) to ensure reliability. 
-   **Client ↔ Client**: Both maintain WebSockets to Cloudflare Workers, which route signaling to a shared Durable Object.
-   **Server ↔ Server**: Uses a GCP Pub/Sub topic with per-server filtered subscriptions.
-   **Server ↔ Client**: A hybrid path using both HTTP/PubSub and WebSockets.

### 3. Data Plane: "How does actual data flow?"
The data plane uses a transport selection waterfall:
1.  **Direct UDP** via WebRTC host candidates.
2.  **STUN-assisted UDP** via server-reflexive candidates.
3.  **TURN over UDP relay**.
4.  **TURN over TCP/443 relay**.
5.  **WebSocket relay** through a Durable Object as a last resort.

## Multi-Channel Architecture
Each connection has multiple `DataChannel`s for specific purposes:
-   **Control Channel**: Reliable and ordered (heartbeats, capability negotiation).
-   **Data Channel**: Reliable and unordered (state sync, RPC, file transfer).
-   **Stream Channel**: Unreliable and unordered (real-time telemetry, position updates, sensor data).
