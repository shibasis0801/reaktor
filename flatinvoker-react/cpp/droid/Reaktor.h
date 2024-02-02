#pragma once

#include <JSIInstaller.h>
#include <droid/Installer.h>

// TODO move this to cpp

#define JAVA_DESCRIPTOR(fqcn) static auto constexpr kJavaDescriptor = fqcn;

struct JavaNativeModule: jni::JavaClass<JavaNativeModule> {
    JAVA_DESCRIPTOR("Lcom/facebook/react/bridge/ReactContextBaseJavaModule;")
    std::string getName() {
        static const auto getName = getClass()->getMethod<jstring()>("getName");
        return getName(self())->toString();
    }
};

struct JReaktor: public jni::JavaClass<JReaktor> {
    static auto constexpr kJavaDescriptor = "Lcom/myntra/appscore/batcave/Reaktor;";

    static void install(
            jni::alias_ref<JReaktor> _,
            jlong reactPointer,
            jni::alias_ref<react::CallInvokerHolder::javaobject> jsCallInvokerHolder,
            jni::alias_ref<react::CallInvokerHolder::javaobject> nativeCallInvokerHolder
    ) {
        Reaktor::Log.Verbose("Inside Native install");
        auto runtime = reinterpret_cast<jsi::Runtime *>(reactPointer);
        auto jsCallInvoker = jsCallInvokerHolder->cthis();
        GUARD(jsCallInvoker);
        auto nativeCallInvoker = nativeCallInvokerHolder->cthis();
        GUARD(nativeCallInvoker);
        Reaktor::install(runtime, jsCallInvoker->getCallInvoker(), nativeCallInvoker->getCallInvoker());
    }

    static void call(
            jni::alias_ref<JReaktor> _,
            jni::JString data
    ) {
        auto value = data.toString();
        Reaktor::Log.Verbose(value);
        Reaktor::Log.Verbose(value);
        Reaktor::call(value);
    }

    static void addModule(
            jni::alias_ref<JReaktor> _,
            jni::JString name,
            const jni::alias_ref<jni::JObject>& instance
    ) {
        Reaktor::addModule(instance, name.toStdString());
    }

    static void registerNatives() {
        javaClassStatic()->registerNatives({
                                                   makeNativeMethod("install", JReaktor::install),
                                                   makeNativeMethod("call", JReaktor::call),
                                                   makeNativeMethod("addModule", JReaktor::addModule)
                                           });
    }
};

jint JNI_OnLoad(JavaVM* vm, void*) {
    return jni::initialize(vm, [] {
        JReaktor::registerNatives();
        Reaktor::registerNatives();
//        Reaktor::test();
    });
}
