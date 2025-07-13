#pragma once

#include <droid/DroidBase.h>
#include <common/Invokable.h>

struct KotlinInvokable: jni::JavaClass<KotlinInvokable> {
    JAVA_DESCRIPTOR("Ldev/shibasis/flatinvoker/ffi/Invokable;");
    void invokeSync(jbyteArray array) {
        static const auto method = getClass()->getMethod<jlong(jbyteArray)>("invokeSync");
        method(self(), array);
    }
};

//class AndroidInvokable: Invokable {
//    // reset after object is no longer needed
//    jni::global_ref<KotlinInvokable> instance;
//public:
//    explicit AndroidInvokable(jni::alias_ref<KotlinInvokable> instance): instance(jni::make_global(instance)) {}
//    long invokeSync(const flexbuffers::Vector &payload) override;
//};