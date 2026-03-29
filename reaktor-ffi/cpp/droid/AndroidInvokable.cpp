#include <droid/AndroidInvokable.h>
#include <common/Engine.h>
#include <common/HermesHello.h>
#include <flatbuffers/flexbuffers.h>

jni::global_ref<jni::JObject> instance;

struct JTester : public jni::JavaClass<JTester> {
    static constexpr auto kJavaDescriptor = JAVA_DESCRIPTOR("dev.shibasis.reaktor.ffi.Tester");

    static int sum() {
        auto method = instance->getClass()->getMethod<jlong(jbyteArray)>("invokeSync");
        flexbuffers::Builder builder;
        builder.Vector([&] {
            builder.String("FlexInvokable");
            builder.String("add");
            builder.Int(0);
            builder.Int(1);
            builder.Int(2);
        });
        builder.Finish();
        auto data = builder.GetBuffer();
        auto byteArray = jni::JArrayByte::newArray(data.size());
        byteArray->setRegion(0, data.size(), reinterpret_cast<const jbyte *>(data.data()));
        return method(instance, byteArray.release());
    }

    static int test(jni::alias_ref<JTester> self) {
        instance = jni::make_global(self);
        return sum();
    }

    static int testHermes(jni::alias_ref<JTester> self) {
        Engine::start();
        return 0;
    }

    // Integration test: evaluates JS in Hermes and returns the result
    static jni::local_ref<jni::JString> hermesHello(jni::alias_ref<JTester> self) {
        return jni::make_jstring(Reaktor_HermesHello());
    }

    static void registerNatives() {
        javaClassStatic()->registerNatives({
            makeNativeMethod("test", JTester::test),
            makeNativeMethod("testHermes", JTester::testHermes),
            makeNativeMethod("hermesHello", JTester::hermesHello)
        });
    }
};

jint JNI_OnLoad(JavaVM *vm, void*) {
    return facebook::jni::initialize(vm, [] {
        JTester::registerNatives();
    });
}
