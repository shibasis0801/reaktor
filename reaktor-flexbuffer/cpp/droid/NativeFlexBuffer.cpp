#include <jni.h>
#include <common/NativeFlexBuffer.h>

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_dev_shibasis_reaktor_flexbuffer_NativeFlexBridge_flexHello(
        JNIEnv* env,
        jobject
) {
    const auto bytes = Reaktor_FlexHelloBytes();
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data())
    );
    return result;
}
