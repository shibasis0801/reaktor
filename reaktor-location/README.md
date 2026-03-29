# reaktor-location

`reaktor-location` provides location access through shared adapters.

## Current surface

- `Location`
- `LocationAdapter`
- `MapAdapter`
- Android implementation
- Darwin implementation

## Goal

Shared code should be able to request location without knowing whether the host is Android or iOS.

## Status

This module is intentionally small. It is an adapter surface, not a full mapping SDK.
