#pragma once

#include <droid/DroidBase.h>

struct JavaJni : public jni::JavaClass<JavaJni> {
    JAVA_DESCRIPTOR("Lcom/jetbrains/kmm/shared/JavaJni;");
    static jni::local_ref<jni::JByteBuffer> sendMessage(jni::alias_ref<JavaJni> _, jni::alias_ref<jni::JByteBuffer> buffer);
    static jni::local_ref<jni::JByteBuffer> getByteBuffer(jni::alias_ref<JavaJni> _);
    static jni::local_ref<jni::JByteBuffer> echoByteBuffer(jni::alias_ref<JavaJni> _, jni::alias_ref<jni::JByteBuffer> buffer);
    static jni::local_ref<jni::JByteBuffer> execute(jni::alias_ref<JavaJni> _, jni::alias_ref<jni::JByteBuffer> buffer);
    static jni::local_ref<jni::JByteBuffer> parseJson(jni::alias_ref<JavaJni> _, jni::alias_ref<jni::JString> jsonString);
    static void registerNatives();


};
