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
        jni::alias_ref<JavaMessageSender> bifrost,
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
        builder.Int(sum);
        builder.Finish();

        // Write Output Buffer
        auto data = builder.GetBuffer();
        auto outputBuffer = jni::JByteBuffer::allocateDirect(data.size());
        std::memcpy(const_cast<uint8_t *>(outputBuffer->getDirectBytes()), data.data(), data.size());
        return outputBuffer;

    }

    static void registerNatives() {
        javaClassStatic()->registerNatives({
            makeNativeMethod("sendMessage", JavaMessageSender::sendMessage)
        });
    }
};

jint JNI_OnLoad(JavaVM *vm, void*) {
    return jni::initialize(vm, [] {
        JavaMessageSender::registerNatives();
    });
}



//struct JavaChannel {
//    jni sendMessage(message) {
//        messageReceiever(message)
//    }
//};
//
//void sendMessage(string receiver) {
//    if (receiver == "java") {
//        JavaReceiver.onMessage(payload);
//    }
//}
//
//void messageReceiver(string message) {
//
//}