#include <droid/DroidChannel.h>
#include <jni.h>


extern "C"
JNIEXPORT jint JNICALL
Java_com_jetbrains_kmm_shared_Reaktor_readInt(JNIEnv *env, jobject thiz) {
    return 42;
}





//struct JavaChannel {
//    jni sendMessage(message) {
//        messageReceiever(message)
//    }
//};
//
//void sendMessage(string receiver) {
//    if (receiver == "java") {
//        JavaReceiver.onMessage(payload);
//    }
//}
//
//void messageReceiver(string message) {
//
//}