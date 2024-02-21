#include <droid/JavaJni.h>
#include "JavaJni.h"
#include "flatbuffers/idl.h"
#include "flatbuffers/buffer.h"
#include <chrono>

jni::local_ref<jni::JByteBuffer> JavaJni::sendMessage(
        jni::alias_ref<JavaJni> _,
        jni::alias_ref<jni::JByteBuffer> buffer
) {
    if (!buffer->isDirect()) throw std::runtime_error("Indirect buffer shibasis");

    // Read Input Buffer
    auto root = flexbuffers::GetRoot(buffer->getDirectBytes(), buffer->getDirectSize());
    auto vec = root.AsVector();
    auto first = vec[0].AsInt64();
    auto second =  vec[1].AsInt64();

    // Perform Operation and Create Result FlexBuffer
    auto sum = first + second;
    flexbuffers::Builder builder(1024);
    builder.Vector([&]() {
        builder.Int(first);
        builder.Int(second);
        builder.Int(sum);
    });
    builder.Finish();
    auto x = builder.GetSize();

    // Write Output Buffer
    auto data = builder.GetBuffer();
    // todo Object pool direct byte buffers, allocations are expensive
    auto outputBuffer = jni::JByteBuffer::allocateDirect(builder.GetSize());
    outputBuffer->order(jni::JByteOrder::nativeOrder());
    std::memcpy(const_cast<uint8_t *>(outputBuffer->getDirectBytes()), data.data(), builder.GetSize());
    return outputBuffer;
}

// Timer lambda
auto measureTime = [](auto&& func, auto&&... args) {
    auto start = std::chrono::high_resolution_clock::now();

    // Forwarding arguments to the function
    std::forward<decltype(func)>(func)(std::forward<decltype(args)>(args)...);

    auto finish = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(finish - start);
};

//inline void parse(jni::alias_ref<jni::JString> jsonString) {
//    flatbuffers::Parser parser;
//    flexbuffers::Builder builder(1024);
//
//    auto start = std::chrono::high_resolution_clock::now();
//    auto result = parser.ParseFlexBuffer(jsonString->toStdString().c_str(), nullptr, &builder);
//    auto finish = std::chrono::high_resolution_clock::now();
//    auto time = std::chrono::duration_cast<std::chrono::milliseconds>(finish - start);
//    __android_log_print(ANDROID_LOG_DEBUG, "jsonparsetime", "Time: %d\n", time.count());
//}


jni::local_ref<jni::JByteBuffer> JavaJni::parseJson(
        jni::alias_ref<JavaJni> _,
        jni::alias_ref<jni::JString> jsonString) {
    flatbuffers::Parser parser;
    flexbuffers::Builder builder(1024);

    auto result = parser.ParseFlexBuffer(jsonString->toStdString().c_str(), nullptr, &builder);

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

jni::local_ref<jni::JByteBuffer> JavaJni::getByteBuffer(
        jni::alias_ref<JavaJni> _
) {
    auto outputBuffer = jni::JByteBuffer::allocateDirect(1);
    outputBuffer->order(jni::JByteOrder::nativeOrder());
    return outputBuffer;
}

jni::local_ref<jni::JByteBuffer> JavaJni::echoByteBuffer(
        jni::alias_ref<JavaJni> _,
        jni::alias_ref<jni::JByteBuffer> buffer
) {
    buffer->getDirectBytes()[0] = 1;
    return buffer->order(jni::JByteOrder::nativeOrder());
}



jni::local_ref<jni::JByteBuffer> JavaJni::execute(
        jni::alias_ref<JavaJni> _,
        jni::alias_ref<jni::JByteBuffer> buffer
) {
    auto command = flexbuffers::GetRoot(buffer->getDirectBytes(), buffer->getDirectSize()).AsMap();
    auto className = command["className"].AsString().str();
    auto functionName = command["functionName"].AsString().str();
    auto payload = command["payload"].AsMap()["data"].AsVector();

    int sum = 0;
    repeat(i, payload.size()) {
        sum += payload[i].AsInt64();
    }

    flexbuffers::Builder builder(1024);
    builder.Vector([&]() {
        builder.String(className);
        builder.String(functionName);
        builder.Int(sum);
    });
    builder.Finish();

    auto data = builder.GetBuffer();
    // Object pool direct byte buffers, allocations are expensive
    auto outputBuffer = jni::JByteBuffer::allocateDirect(builder.GetSize());
    outputBuffer->order(jni::JByteOrder::nativeOrder());
    std::memcpy(const_cast<uint8_t *>(outputBuffer->getDirectBytes()), data.data(), builder.GetSize());
    return outputBuffer;
}

void JavaJni::registerNatives() {
    javaClassStatic()->registerNatives({
        makeNativeMethod("sendMessage", JavaJni::sendMessage),
        makeNativeMethod("getByteBuffer", JavaJni::getByteBuffer),
        makeNativeMethod("echoByteBuffer", JavaJni::echoByteBuffer),
        makeNativeMethod("execute", JavaJni::execute),
        makeNativeMethod("parseJson", JavaJni::parseJson)
    });
}
