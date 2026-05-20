# FlexBuffers: The Zero-Copy Data Layer

FlexBuffers, part of Google's FlatBuffers project, is a self-describing binary format that Reaktor uses as its universal data format. Unlike JSON, FlexBuffers data can be accessed without parsing or deserialization, leading to massive performance gains.

## The Role of FlexBuffers

Reaktor uses FlexBuffers in three critical roles:

### 1. FFI Wire Format
When data crosses language boundaries (e.g., Kotlin ↔ C++ ↔ JavaScript), it must be serialized. 

- **Zero-copy on the reader side**: The receiver reads directly from the byte buffer.
- **Language-agnostic**: Can be called from Kotlin/Native, Swift, Dart, C#, Erlang, and JavaScript.
- **Schema DSL Integration**: Annotated Kotlin schemas generate typed accessors for every language.

### 2. Object Cache (ObjectStore)
Reaktor's `ObjectStore` (reaktor-db) is a key-value store backed by SQLite. 

- **Cache hit without deserialization**: Reading a byte buffer and accessing fields directly drops access time from ~2ms to 0.1ms.
- **Partial reads**: Only the required fields are read, whereas JSON requires parsing the entire string.
- **Cache-to-wire zero-copy**: When sending a cached object over the network or FFI, the byte buffer is sent directly.

### 3. Network Transport
FlexBuffers is the wire format for all Reaktor transports, including HTTP, Mesh DataChannels, Pub/Sub, and Actor mailboxes. Content negotiation (JSON for debugging, FlexBuffer for production) is handled at the Service layer.

## Schema DSL: Single Source of Truth

Reaktor uses annotated Kotlin data classes with FlexBuffer serialization as the canonical schema definition. These are processed by the `reaktor-compiler` (KSP) to generate artifacts for every target:

- **Kotlin**: Service layer, ObjectStore, and graph port types.
- **TypeScript**: Interface, decoder, and JSON type guards.
- **Dart**: Flutter bridge classes.
- **Swift**: SwiftUI bridge structs.
- **C++**: High-performance Highway SIMD accessors.
- **SQL**: Database table generation and migration scripts.
- **API Routes**: HTTP route handlers and OpenAPI specifications.
