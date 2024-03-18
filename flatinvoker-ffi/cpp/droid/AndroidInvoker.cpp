#include <droid/AndroidInvoker.h>
#include <jni.h>
#include <droid/DroidBase.h>
#include <fbjni/detail/Common.h>
#include <flatbuffers/flexbuffers.h>


struct JTester : public jni::JavaClass<JTester> {
    JAVA_DESCRIPTOR("Ldev/shibasis/flatinvoker/ffi/Tester;");

    static int test(jni::alias_ref<JTester> self) {
        return 42;
    }

    static void registerNatives() {
        javaClassStatic()->registerNatives({
            makeNativeMethod("test", JTester::test)
        });
    }
};

jint JNI_OnLoad(JavaVM *vm, void*) {
    return facebook::jni::initialize(vm, [] {
        JTester::registerNatives();
    });
}

