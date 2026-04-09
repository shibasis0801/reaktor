# reaktor-react

> **Stability: Paused** - Not actively maintained. Kept for historical reference.

`reaktor-react` was Reaktor's React Native integration layer, providing JSI (JavaScript Interface) bridge support.

## What it contained

- `JSIManager` - Native module interface for React Native
- `Invoker` - Function invocation across JSI boundary
- `FlowHandle` - Reactive flow bridging to React Native
- `Promise` - Promise wrapper type
- `NetworkModule` - Network API exposed to React Native
- Android and iOS platform-specific JSI bindings

## Status

This module is not an active investment area. It is kept in the repo because parts of the older bridge/runtime work still live here. It targets React Native 0.68.5.

If you are adding new cross-platform app runtime features, prefer the actively maintained modules (`reaktor-graph`, `reaktor-ffi`, `reaktor-ui`) instead.
