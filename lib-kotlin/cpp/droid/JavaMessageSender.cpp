#include <droid/JavaMessageSender.h>

jni::local_ref <jni::JByteBuffer> JavaMessageSender::sendMessage(jni::alias_ref <JavaMessageSender> jms,
                                                                 jni::alias_ref <jni::JByteBuffer> buffer) {
    return buffer->order(jni::JByteOrder::nativeOrder());
}

void JavaMessageSender::registerNatives() {
    javaClassStatic()->registerNatives({
        makeNativeMethod("sendMessage", JavaMessageSender::sendMessage),
    });
}
