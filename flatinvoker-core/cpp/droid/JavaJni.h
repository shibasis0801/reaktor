#pragma once

#include <droid/DroidBase.h>

struct JavaJni : public jni::JavaClass<JavaJni> {
    JAVA_DESCRIPTOR("Ldev/shibasis/flatinvoker/core/JavaJni;");
    static jni::local_ref<jni::JByteBuffer> parseJson(jni::alias_ref<JavaJni> _, jni::alias_ref<jni::JString> jsonString);
    static void registerNatives();
};
