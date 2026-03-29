# reaktor-graph

`reaktor-graph` is Reaktor's graph runtime.

## What it adds on top of `reaktor-core` and `reaktor-graph-port`

- graph and node lifecycle
- navigation and back stack management
- scoped dependency injection
- coroutine scope ownership
- route nodes and container nodes
- shared service integration
- JSON export helpers for graph inspection

## Main runtime types

- `Graph`
- `Node`
- `BasicNode`
- `ControllerNode`
- `RouteNode`
- `ContainerNode`
- `Service`
- `ServiceNode`

## Core ideas

### Graph as runtime scope

A graph owns:
- nodes
- DI scope
- lifecycle state
- coroutine scope
- navigation state
- local ports and auto-wiring

### Nodes communicate through typed ports

Nodes do not reach into each other directly. They communicate through provider and consumer ports wired by explicit edges or `autoWire()`.

### Navigation is graph-native

Navigation is not bolted on as a separate router. It is part of the graph runtime through route bindings, navigation edges, and container nodes.

### Services are part of the same model

Service contracts sit inside the graph model instead of becoming a parallel architecture.

The current service layer preserves:
- typed request/response models
- route and method identity
- transport metadata
- interceptors and policy hooks

## Current maturity

This is one of the most mature parts of Reaktor and is actively used by BestBuds.
