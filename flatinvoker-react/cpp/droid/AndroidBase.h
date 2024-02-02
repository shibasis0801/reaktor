#pragma once

#include <Base.h>
#include <jni.h>
#include <fbjni/fbjni.h>
#include <ReactCommon/CallInvoker.h>
#include <ReactCommon/CallInvokerHolder.h>

namespace jni = facebook::jni;

namespace Reaktor {
    struct AndroidLogger : public Logger {
        AndroidLogger(const std::string &TAG);

        void Verbose(const std::string &message) override;
        void Error(const std::string &errorMessage) override;
    };

    static AndroidLogger Log("Reaktor");

    void throwIfJNIError(const std::string &location);

    using jniRef = jni::alias_ref<jobject>;
    // We can't check unions, so instead breaking it down to double / object
    using jniType = std::variant<jobject, jvalue>;
}