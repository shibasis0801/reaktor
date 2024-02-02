# Pillars on which this works -> Invoker, Converter 
Invoker is supplied a Converter

Invoker -> JavaInvoker, DarwinInvoker
Converter -> JNI-CPP Converter, OBJC-CPP Converter, JNI-JSI Converter, OBJC-JSI Converter, FlatBuffer-JSI Converter, FlatBuffer-Cpp Converter

## Type Support 

Primitives
1. Double
2. String
3. Boolean
4. Integer
5. Long

Containers
1. Array
2. HashMap
3. Object (through FlatBuffers)
4. etc

Async Containers 
1. Promise
2. Flow

Additional Notes: 
1. Sealed Data Classes become Discriminated Unions
2. Bring Flutter Support

Testing: 
1. The tester app and the test suite must benchmark all calls with serialization times
2. The native functionality and end-to-end flows must be integration and stress tested on each platform
3. We must maintain a performance comparison table to compare this with bridge, turbo modules and method channels

















