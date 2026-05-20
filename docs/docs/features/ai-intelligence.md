# AI-Native Intelligence: The Three Levels

Reaktor is the first application engine where AI intelligence is a first-class primitive, not an afterthought. AI integration operates at three levels, each building on the one below.

## Level 1: Reaktor MCP Server

The `reaktor-mcp` module is a proper Model Context Protocol (MCP) server that exposes Reaktor's internals as tools for any MCP-compatible LLM (e.g., Claude Code, Cursor).

-   **Graph Introspection**: Query the running graph's topology, node states, and port types.
-   **Schema Definitions**: Retrieve Schema DSL types and their validation rules.
-   **Module Registry**: List available modules and their APIs.
-   **Documentation**: Access full API documentation and example patterns.
-   **Build & Deploy**: Trigger builds, run tests, and deploy specific runtimes.

## Level 2: Blueprint Copilot

The `Blueprint Copilot` is an AI assistant integrated directly into the Blueprint editor. It uses the MCP server for context and adds editor-specific intelligence.

-   **Node Implementation Generation**: Automatically generate a complete, compilable node implementation based on the node's ports and surrounding graph context.
-   **Adapter Node Generation**: When a developer draws an edge between mismatched types, the Copilot generates a transformation node that transforms type B into type A.
-   **Schema Evolution**: Identifies affected nodes and ports when a Schema DSL type is modified, generating migration code and highlighting breaking changes.
-   **Graph Pattern Suggestions**: Recognizes common patterns (e.g., repository + interactor + screen) and suggests completing them.

## Level 3: Runtime AI Nodes (reaktor-ai)

An AI node in the graph is just another node with typed ports. The graph runtime handles its lifecycle, caching, fallbacks, and telemetry identically to any other node.

| Node Type | Purpose | Use Case |
| --- | --- | --- |
| **LLMNode** | Prompt + context in, structured response out. | Natural language processing and content generation. |
| **EmbeddingNode** | Text or document in, vector embedding out. | Semantic search and similarity matching. |
| **ClassifierNode** | Input data in, classification + confidence out. | Content moderation and intent detection. |
| **RAGNode** | Query + retrieval context in, augmented response out. | Knowledge-grounded Q&A. |
| **AgentNode** | Goal + available tools in, action sequence out. | Multi-step reasoning and automated workflows. |

### How They Compose

The three levels form a feedback loop:

1.  **MCP Server** provides the context (the graph, schemas, and docs).
2.  **Blueprint Copilot** uses that context to generate code and manage the graph.
3.  **Runtime AI Nodes** execute at runtime and feed their telemetry back into the MCP server, allowing the Copilot to suggest optimizations.
