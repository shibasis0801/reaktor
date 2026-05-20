# Getting Started with Reaktor

Initialize your system specification in under 30 minutes. Reaktor's CLI scaffolds a complete multi-runtime graph, ready for local development or edge deployment.

## 1. Installation

Install the Reaktor command-line interface globally via npm.

```bash
npm install -g @reaktor/cli
```

## 2. Initialize a Project

Create a new Reaktor project using the interactive scaffolder. Choose from templates like `social`, `ecommerce`, or `empty`.

```bash
reaktor create my-app --template social
```

## 3. Define Your First Schema

Reaktor uses annotated Kotlin data classes as the single source of truth for all runtimes.

```kotlin
@ReaktorSchema
data class UserProfile(
    val id: String,
    val username: String,
    val bio: String?,
    @Default("0") val level: Int
)
```

## 4. Launch the Blueprint Editor

The Blueprint editor provides a live view of your running graph. Changes in the editor generate code in real-time.

```bash
cd my-app
reaktor blueprint
```

## Next Steps

Explore the **[Graph Runtime](./architecture/graph-runtime)** to understand how nodes and ports connect your application logic.
