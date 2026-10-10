#include <exception>
#include <fbjni/fbjni.h>
#include <reaktor/interop/Runtime.hpp>

namespace jni = facebook::jni;
using reaktor::interop::Runtime;

struct Owner {
    jni::global_ref<jni::JObject> host;
    Runtime runtime;
    explicit Owner(jni::alias_ref<jni::JObject> host): host(jni::make_global(host)), runtime(
        [this](std::string_view module, std::string_view operation, std::string_view request) {
            auto method = this->host->getClass()->getMethod<jni::local_ref<jni::JString>(jni::alias_ref<jni::JString>, jni::alias_ref<jni::JString>, jni::alias_ref<jni::JString>)>("invokeHost");
            return method(this->host, jni::make_jstring(std::string(module)), jni::make_jstring(std::string(operation)), jni::make_jstring(std::string(request)))->toStdString();
        }) {}
};

struct JRuntime : jni::JavaClass<JRuntime> {
    static constexpr auto kJavaDescriptor = "Ldev/shibasis/reaktor/ffi/interop/kernel/AndroidKernel;";
    static Owner& owner(jlong handle) {
        if (!handle) throw std::invalid_argument("Interop runtime is closed");
        return *reinterpret_cast<Owner*>(handle);
    }
    static jlong create(jni::alias_ref<JRuntime> self) { return reinterpret_cast<jlong>(new Owner(self)); }
    static void destroy(jni::alias_ref<JRuntime>, jlong handle) {
        owner(handle).runtime.checkCanClose();
        delete &owner(handle);
    }
    static jni::local_ref<jni::JString> nativeCall(jni::alias_ref<JRuntime>, jlong handle,
        jni::alias_ref<jni::JString> module, jni::alias_ref<jni::JString> operation, jni::alias_ref<jni::JString> request) {
        return jni::make_jstring(owner(handle).runtime.callNative(module->toStdString(), operation->toStdString(), request->toStdString()));
    }
    static jni::local_ref<jni::JString> typescriptCall(jni::alias_ref<JRuntime>, jlong handle,
        jni::alias_ref<jni::JString> module, jni::alias_ref<jni::JString> operation, jni::alias_ref<jni::JString> request) {
        return jni::make_jstring(owner(handle).runtime.callTypeScript(module->toStdString(), operation->toStdString(), request->toStdString()));
    }
    static void evaluateSource(jni::alias_ref<JRuntime>, jlong handle, jni::alias_ref<jni::JString> source, jni::alias_ref<jni::JString> name) {
        owner(handle).runtime.evaluate(source->toStdString(), name->toStdString());
    }
    static void registerNatives() {
        javaClassStatic()->registerNatives({ makeNativeMethod("create", create), makeNativeMethod("destroy", destroy),
            makeNativeMethod("nativeCall", nativeCall), makeNativeMethod("typescriptCall", typescriptCall),
            makeNativeMethod("evaluateSource", evaluateSource) });
    }
};

jint JNI_OnLoad(JavaVM* vm, void*) { return jni::initialize(vm, [] { JRuntime::registerNatives(); }); }
