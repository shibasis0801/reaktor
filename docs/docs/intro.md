import Blueprint from '@site/src/components/Blueprint';

# Reaktor: The Unreal Engine of App & Server Development

Reaktor is a Kotlin Multiplatform application framework that models apps and services as directed graphs of typed nodes. It is designed to be a working polyglot runtime, with a goal of becoming the "Unreal Engine" for general application development.

<Blueprint 
  title="Core Architecture: Graph of Graphs"
  callouts={[
    { x: '30%', y: '30%', label: 'ConsumerPort', description: 'Entry point for data. Validates incoming FlexBuffer streams against the node schema.' },
    { x: '70%', y: '30%', label: 'ProviderPort', description: 'Exit point for data. Emits typed responses to downstream nodes or UI bridges.' },
    { x: '50%', y: '70%', label: 'NodeLifecycle', description: 'Manages the state transition from Created to Active. Supports hot-swapping at runtime.' }
  ]}
>
  <img src="/img/graph-blueprint.svg" style={{ width: '300px' }} />
</Blueprint>

## Executive Summary

At its core, Reaktor addresses the chasm between a working framework and a dominant ecosystem. By replicating the patterns that made Unreal Engine dominant in the gaming world, Reaktor aims to provide a unified runtime that works across every target.

- **Unified Model**: Directed graphs of typed nodes for entire systems.
- **Polyglot Runtime**: Native performance on Android, iOS, JVM, Cloudflare Workers, and Desktop.
- **Developer Experience**: Visual scripting (Blueprints), hot reload, and graph visualization.

## The Unreal Engine Playbook

Unreal Engine achieved dominance through six pillars, which Reaktor maps onto its own domain:

| Pillar | Unreal Engine | Reaktor Equivalent |
| --- | --- | --- |
| **Flagship Product** | *Fortnite*: Proved the engine at scale. | *Bestbuds*: The showcase app handling real traffic and complexity. |
| **Low-Barrier Entry** | *Blueprints*: Visual scripting for non-programmers. | Schema DSL, CLI scaffolding, and GenUI for rapid shipping. |
| **Asset Marketplace** | Unreal Marketplace with plugins and models. | **Reaktor Module Registry**: Pre-built auth, payments, chat, etc. |
| **Cross-Platform** | PC, console, mobile, VR from one codebase. | Android, iOS, JVM, Workers, Desktop from one graph. |
| **Developer Experience** | Editor, live preview, hot reload, profiling. | CLI, hot reload, graph visualizer, telemetry dashboard. |
| **Community & Ecosystem** | Millions of developers, learning resources. | Open-source core, contributor program, tutorials. |
