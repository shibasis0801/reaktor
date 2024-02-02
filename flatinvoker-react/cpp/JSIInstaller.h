#pragma once

#include <droid/AndroidBase.h>
#include <droid/bindings/Functions.h>

namespace jni = facebook::jni;
namespace Reaktor {

    void install(
            jsi::Runtime *_runtime,
            std::shared_ptr<react::CallInvoker> jsCallInvoker,
            std::shared_ptr<react::CallInvoker> nativeCallInvoker
    ) {
        getLink().install(_runtime, jsCallInvoker, nativeCallInvoker);
//        test();
    }

    void call(const std::string &data) {
        // You cannot do a invokeSync from a jsCallInvoker
        // https://github.com/facebook/react-native/blob/0b6e5b4f9195fd4391911ac831b79ae281b99e85/ReactCommon/cxxreact/Instance.cpp
//        getLink().jsCallInvoker->invokeAsync([&](){
//            auto &runtime = getLink().getRuntime();
//            auto Observable = runtime.global().getPropertyAsFunction(runtime, "Observable");
//
//            auto observable = Observable.callAsConstructor(runtime);
//            auto notify = observable.getObject(runtime).getPropertyAsFunction(runtime, "notify");
//
//            auto result = notify.call(runtime, jsi::Value(jsi::String::createFromUtf8(runtime, "Something")));
//
//            string str = result.asString(runtime).utf8(runtime);
//            Log.Verbose(str);
//        });
    }
}
