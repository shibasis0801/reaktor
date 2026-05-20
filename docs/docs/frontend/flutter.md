# Flutter Integration

Flutter is a powerful UI framework using the Dart language. While Dart doesn't share a runtime with Kotlin, Reaktor integrates seamlessly using Flutter's `MethodChannel` and FlexBuffer binary messages.

## Bridge Architecture

### `reaktor_flutter` (pub.dev)
A Flutter plugin that wraps the Reaktor native runtime. It handles the binary communication between the Flutter UI and the Reaktor service layer.

### Key Components

-   `ReaktorService.call<T>('ServiceName', 'MethodName', params)`: A Dart method that sends a FlexBuffer-encoded request and returns a typed Dart object.
-   `ReaktorAuth`: A Dart class wrapping Reaktor's auth system, exposing a `userStream` for `StreamBuilder` integration.
-   `ReaktorStore.watch<T>('KeyName')`: A `ValueNotifier`-based reactive store that updates the UI when the underlying value changes.
-   `Schema-generated Dart`: Annotated Kotlin schemas are used to generate Dart data classes with `fromFlexBuffer()` and `toFlexBuffer()` methods.

## Full Type Safety

Unlike generic JSON-based platform channels, Reaktor's integration provides full type safety. The Reaktor compiler reads the Kotlin schemas and generates all the necessary Dart models and service stubs.

The Flutter developer writes zero Kotlin code, focusing entirely on their UI and leveraging Reaktor's robust data layer, auth flows, and real-time mesh networking.
