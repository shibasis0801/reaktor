#pragma once

#include <droid/DroidBase.h>

struct JavaMessageSender: public jni::JavaClass<JavaMessageSender> {
    JAVA_DESCRIPTOR("Lcom/jetbrains/kmm/shared/message/JavaMessageSender;");
    static jni::local_ref<jni::JByteBuffer> sendMessage(jni::alias_ref<JavaMessageSender> jms, jni::alias_ref<jni::JByteBuffer> buffer);
    static void registerNatives();
};