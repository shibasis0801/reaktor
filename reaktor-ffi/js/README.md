# reaktor-ffi JS runtime notes

This folder exists for the JavaScript-side runtime that pairs with the native bridge strategy.

## Current direction

- Hermes is the JavaScript engine used for the native bridge path
- Metro is used because it is still the most practical mobile-oriented bundler for this runtime shape
- React Native is not a required framework dependency; the goal is a framework-agnostic bridge

## Why this exists

The intent is to support native-hosted JavaScript execution without forcing product code to adopt React Native's full runtime model.

## Non-goals right now

- complete React Native compatibility
- Turbo Module parity
- broad public API stability

This area is still experimental and secondary to the Kotlin-native bridge itself.
