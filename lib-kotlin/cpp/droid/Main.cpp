#include <droid/Main.h>

jint JNI_OnLoad(JavaVM *vm, void*) {
    return jni::initialize(vm, [] {
        JavaJni::registerNatives();
        JavaMessageSender::registerNatives();
    });
}

