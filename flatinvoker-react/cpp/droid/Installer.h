#pragma once
#include <droid/AndroidBase.h>
#include <modules/LayoutDatabase.h>
#include <modules/NetworkModule.h>
#include <droid/Invoker.h>
#include <droid/bindings/Functions.h>

namespace Reaktor {
    jsi::Object createHostObject(
            jni::alias_ref<jni::JObject> instance,
            const std::string &name
    ) {
        auto platformInvoker = make_shared<AndroidInvoker>(instance);


        if (name == "LayoutDatabase") {
            auto hostObject = make_shared<LayoutDatabase>(std::move(platformInvoker), Log);
            return getLink().createFromHostObject(hostObject);
        }
        if (name == "NetworkModule") {
            auto hostObject = make_shared<NetworkModule>(std::move(platformInvoker), Log);
            return getLink().createFromHostObject(hostObject);
        }

        throw ReaktorException("Not Supported: " + name);
    }

    void addModule(
            jni::alias_ref<jni::JObject> instance,
            const std::string &name
    ) {
        getLink().createGlobalProperty(name, createHostObject(instance, name));
    }


    void registerNatives() {
        NoArgNativeFunction::registerNatives();
        SingleArgNativeFunction::registerNatives();
    }
}

/*
 * Optimise re-init for temporary modules
 */