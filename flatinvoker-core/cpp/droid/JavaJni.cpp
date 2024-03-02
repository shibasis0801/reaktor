#include <droid/JavaJni.h>
#include "JavaJni.h"
#include "flatbuffers/idl.h"
#include "flatbuffers/buffer.h"
#include <chrono>

// Timer lambda
auto measureTime = [](auto&& func, auto&&... args) {
    auto start = std::chrono::high_resolution_clock::now();

    // Forwarding arguments to the function
    std::forward<decltype(func)>(func)(std::forward<decltype(args)>(args)...);

    auto finish = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(finish - start);
};

jni::local_ref<jni::JByteBuffer> JavaJni::parseJson(
        jni::alias_ref<JavaJni> _,
        jni::alias_ref<jni::JString> jsonString) {

    flatbuffers::Parser parser;
    flexbuffers::Builder builder(1024);

    parser.ParseFlexBuffer(jsonString->toStdString().c_str(), nullptr, &builder);

    auto data = builder.GetBuffer();

    // Difficult to prevent a copy and move the data. AFAIK FlatBuffers creates its own buffers internally
    // Direct ByteBuffers are great for passing from C++ to Java (no copy, mem in sharedHeap)
    // But it means a different area from FlatBuffers storage
    auto outputBuffer = jni::JByteBuffer::allocateDirect(builder.GetSize());
    outputBuffer->order(jni::JByteOrder::nativeOrder());
    auto copyTime = measureTime([&]() {
        std::memcpy(const_cast<uint8_t *>(outputBuffer->getDirectBytes()), data.data(), builder.GetSize());
    });
    return outputBuffer;
}

void JavaJni::registerNatives() {
    javaClassStatic()->registerNatives({
        makeNativeMethod("parseJson", JavaJni::parseJson)
    });
}
