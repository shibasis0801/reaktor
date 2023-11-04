#pragma once

#include <droid/DroidBase.h>
#include <core-cpp/base.h>
#include <jni.h>
#include <fbjni/fbjni.h>
#include <fbjni/ByteBuffer.h>
#include <flatbuffers/flexbuffers.h>


struct JavaJni : public jni::JavaClass<JavaJni> {
    JAVA_DESCRIPTOR("Lcom/jetbrains/kmm/shared/JavaJni;");
    static jni::local_ref<jni::JByteBuffer> sendMessage(jni::alias_ref<JavaJni> jms, jni::alias_ref<jni::JByteBuffer> buffer);
    static jni::local_ref<jni::JByteBuffer> getByteBuffer(jni::alias_ref<JavaJni> jms);
    static jni::local_ref<jni::JByteBuffer> echoByteBuffer(jni::alias_ref<JavaJni> jms, jni::alias_ref<jni::JByteBuffer> buffer);
    static jni::local_ref<jni::JByteBuffer> execute(jni::alias_ref<JavaJni> jms, jni::alias_ref<jni::JByteBuffer> buffer);
    static void registerNatives();
};