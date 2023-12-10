#include <droid/JavaJni.h>
#include "JavaJni.h"


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
    // Object pool direct byte buffers, allocations are expensive
    auto outputBuffer = jni::JByteBuffer::allocateDirect(builder.GetSize());
    outputBuffer->order(jni::JByteOrder::nativeOrder());
    std::memcpy(const_cast<uint8_t *>(outputBuffer->getDirectBytes()), data.data(), builder.GetSize());
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
    buffer->getDirectBytes()[0] = 9;
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
        makeNativeMethod("execute", JavaJni::execute)
    });
}
