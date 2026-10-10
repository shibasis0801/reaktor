#include <jni.h>

#include <rsec/rsec.h>

extern "C" JNIEXPORT jint JNICALL
Java_dev_shibasis_reaktor_security_ReaktorSecurityNative_statusOk(
  JNIEnv*,
  jobject)
{
  return RSEC_OK;
}
