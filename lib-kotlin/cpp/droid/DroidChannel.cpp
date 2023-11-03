#include <droid/DroidChannel.h>
#include <jni.h>
#include <fbjni/fbjni.h>
#include <fbjni/ByteBuffer.h>
#include <fbjni/ByteBuffer.h>
#include <string>
#include <flatbuffers/flexbuffers.h>

#define JAVA_DESCRIPTOR(fqcn) static auto constexpr kJavaDescriptor = fqcn;
namespace jni = facebook::jni;

extern "C"
JNIEXPORT jint JNICALL
Java_com_jetbrains_kmm_shared_Reaktor_readInt(JNIEnv *env, jobject thiz) {
    return 42;
}


struct JavaMessageSender: jni::JavaClass<JavaMessageSender> {
    JAVA_DESCRIPTOR("Lcom/jetbrains/kmm/shared/JavaMessageSender;");

    static jni::local_ref<jni::JByteBuffer>  sendMessage(
        jni::alias_ref<JavaMessageSender> jms,
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

    static jni::local_ref<jni::JByteBuffer> getByteBuffer(
            jni::alias_ref<JavaMessageSender> jms
    ) {
        auto outputBuffer = jni::JByteBuffer::allocateDirect(1);
        outputBuffer->order(jni::JByteOrder::nativeOrder());
        return outputBuffer;
    }

    static jni::local_ref<jni::JByteBuffer> echoByteBuffer(
            jni::alias_ref<JavaMessageSender> jms,
            jni::alias_ref<jni::JByteBuffer> buffer
    ) {
        buffer->getDirectBytes()[0] = 9;
        return buffer->order(jni::JByteOrder::nativeOrder());
    }

    static void registerNatives() {
        javaClassStatic()->registerNatives({
            makeNativeMethod("sendMessage", JavaMessageSender::sendMessage),
            makeNativeMethod("getByteBuffer", JavaMessageSender::getByteBuffer),
            makeNativeMethod("echoByteBuffer", JavaMessageSender::echoByteBuffer)
        });
    }
};

jint JNI_OnLoad(JavaVM *vm, void*) {
    return jni::initialize(vm, [] {
        JavaMessageSender::registerNatives();
    });
}

/*
Java <- C++ Sender

Python <- C++ Sender

JS <- C++ Sender


Language -> FlatBuf -> C++ Sender

C++ -> Decode Target Language -> Language Sender -> sendMessage







*/


























