#pragma once

#include <droid/AndroidBase.h>
#include <types/Types.h>

namespace Reaktor {
    struct JNIResult {
        jvalue value;
        DataType dataType;
    };

    JNIResult toJNI(const jsi::Value& value);
    jsi::Value fromJNI(const jvalue &value);
    jsi::Value fromJNI(const jobject &obj);
    jsi::Value fromJNI(jni::global_ref<jobject> ref);
}

