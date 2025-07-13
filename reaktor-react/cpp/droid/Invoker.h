#pragma once

#include <droid/AndroidBase.h>
#include <types/Types.h>
#include <ReaktorHostObject.h>
#include <unordered_map>
#include <types/Async.h>
#include <droid/JNIConverter.h>
#include <types/Async.h>
#include <droid/bindings/Functions.h>


// Needs improvement, Both invokers should return C++ types
// Completely decouple the Invokation and Conversion layers.
// We should not have jmethodID in the function

namespace Reaktor {
    // Only the operator should have jsi return, others should have jni return
    class AndroidInvoker: public PlatformInvoker {
        jni::global_ref<jni::JObject> instance;

    public:
        explicit AndroidInvoker(jni::alias_ref<jni::JObject> instance): instance(jni::make_global(instance)) {}
        jsi::Value operator()(
                const char *name,
                const jsi::Value *args,
                const FunctionDescriptor &descriptor
        ) override;

        jsi::Value invokeStringMethod(
                jmethodID method,
                jvalue *jniArgs
        );

        jsi::Value invokeDoubleMethod(
                jmethodID method,
                jvalue *jniArgs
        );

        jsi::Value invokeHashMapMethod(jmethodID method, jvalue *jniArgs);

        jsi::Value invokeArrayMethod(jmethodID method, jvalue *jniArgs);

        jsi::Value invokePromiseMethod(jmethodID method, jvalue *jniArgs);

        void invokeVoidMethod(const std::string& signature, jvalue *jniArgs);

        ~AndroidInvoker() {
            // Shouldn't be needing this
            getLink().nativeCallInvoker->invokeAsync([instance_ = std::move(instance)]() mutable {
                instance_.reset();
            });
        }

        void invokeVoidMethod(const string &name, const string &signature, jvalue *jniArgs);

        jobject invokeMethod(const string &methodName, jvalue *args, FunctionDescriptor &descriptor);

        jsi::Value invokeFlowMethod(jmethodID method, jvalue *jniArgs);
    };
}
