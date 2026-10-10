#pragma once
#include <jni.h>
#include <string>
#include <vector>
#include "reaktor_web.h"
inline std::string utf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    std::string result;
    jsize length = env->GetStringLength(value);
    for (jsize i = 0; i < length; ++i) {
        uint32_t code = chars[i];
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            code = 0x10000 + ((code - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (code >= 0xd800 && code <= 0xdfff) code = 0xfffd;
        if (code < 0x80) result.push_back(static_cast<char>(code));
        else if (code < 0x800) { result.push_back(0xc0 | (code >> 6)); result.push_back(0x80 | (code & 63)); }
        else if (code < 0x10000) { result.push_back(0xe0 | (code >> 12)); result.push_back(0x80 | ((code >> 6) & 63)); result.push_back(0x80 | (code & 63)); }
        else { result.push_back(0xf0 | (code >> 18)); result.push_back(0x80 | ((code >> 12) & 63)); result.push_back(0x80 | ((code >> 6) & 63)); result.push_back(0x80 | (code & 63)); }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}
inline jstring java_string(JNIEnv *env, const char *value) {
    std::vector<jchar> result;
    const auto *p = reinterpret_cast<const uint8_t *>(value ? value : "");
    while (*p) {
        uint32_t code = *p++;
        if (code >= 0xc0) {
            int count = code < 0xe0 ? 1 : code < 0xf0 ? 2 : 3;
            code &= (1u << (6 - count)) - 1;
            while (count-- && *p) code = (code << 6) | (*p++ & 63);
        }
        if (code > 0xffff) { result.push_back(0xd800 | ((code - 0x10000) >> 10)); result.push_back(0xdc00 | ((code - 0x10000) & 1023)); }
        else result.push_back(static_cast<jchar>(code));
    }
    return env->NewString(result.data(), static_cast<jsize>(result.size()));
}
inline void report(JNIEnv *env, int status) {
    if (status != REAKTOR_WEB_OK && !env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), reaktor_web_last_error());
}
struct CallbackOwner {
    JavaVM *vm = nullptr;
    jobject object = nullptr;
    jmethodID event = nullptr, allows = nullptr;
};
struct CallbackEnv {
    JavaVM *vm; JNIEnv *env = nullptr; bool attached = false;
    explicit CallbackEnv(JavaVM *runtime) : vm(runtime) {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
        }
    }
    ~CallbackEnv() { if (attached) vm->DetachCurrentThread(); }
};
inline void callback_event(void *context, const char *body) {
    auto &owner = *static_cast<CallbackOwner *>(context);
    CallbackEnv thread(owner.vm);
    if (!thread.env) return;
    auto text = java_string(thread.env, body);
    thread.env->CallVoidMethod(owner.object, owner.event, text);
    thread.env->DeleteLocalRef(text);
    if (thread.env->ExceptionCheck()) thread.env->ExceptionClear();
}
inline int callback_allows(void *context, const char *url) {
    auto &owner = *static_cast<CallbackOwner *>(context);
    CallbackEnv thread(owner.vm);
    if (!thread.env) return 0;
    auto text = java_string(thread.env, url);
    auto allowed = thread.env->CallBooleanMethod(owner.object, owner.allows, text);
    thread.env->DeleteLocalRef(text);
    if (thread.env->ExceptionCheck()) { thread.env->ExceptionClear(); return 0; }
    return allowed;
}
inline void callback_release(void *context) {
    auto owner = static_cast<CallbackOwner *>(context);
    CallbackEnv thread(owner->vm);
    if (thread.env) thread.env->DeleteGlobalRef(owner->object);
    delete owner;
}
inline reaktor_web_callbacks make_callbacks(JNIEnv *env, jobject object) {
    auto owner = new CallbackOwner;
    env->GetJavaVM(&owner->vm);
    owner->object = env->NewGlobalRef(object);
    auto type = env->GetObjectClass(object);
    owner->event = env->GetMethodID(type, "event", "(Ljava/lang/String;)V");
    owner->allows = env->GetMethodID(type, "allowsNavigation", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(type);
    return {owner, callback_event, callback_allows, callback_release};
}
extern "C" JNIEXPORT jint JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_abiVersion(JNIEnv *, jobject) { return reaktor_web_abi_version(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_features(JNIEnv *, jobject) { return reaktor_web_features(); }
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_bounds(JNIEnv *env, jobject, jlong handle, jdouble x, jdouble y, jdouble w, jdouble h, jboolean visible) { report(env, reaktor_web_bounds(handle, x, y, w, h, visible)); }
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_load(JNIEnv *env, jobject, jlong handle, jboolean html, jstring value) {
    auto text = utf8(env, value);
    report(env, html ? reaktor_web_load_html(handle, text.c_str()) : reaktor_web_load_url(handle, text.c_str()));
}
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_execute(JNIEnv *env, jobject, jlong handle, jstring value) {
    auto text = utf8(env, value); report(env, reaktor_web_execute(handle, text.c_str()));
}
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_evaluate(JNIEnv *env, jobject, jlong handle, jstring id, jstring value) {
    auto request = utf8(env, id), text = utf8(env, value); report(env, reaktor_web_evaluate(handle, request.c_str(), text.c_str()));
}
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_control(JNIEnv *env, jobject, jlong handle, jint operation, jdouble value) { report(env, reaktor_web_control(handle, operation, value)); }
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_clearAssets(JNIEnv *env, jobject, jlong handle) { report(env, reaktor_web_clear_assets(handle)); }
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_asset(JNIEnv *env, jobject, jlong handle, jstring path, jstring mime, jbyteArray bytes, jstring csp) {
    auto key = utf8(env, path), type = utf8(env, mime), policy = utf8(env, csp);
    auto length = env->GetArrayLength(bytes);
    if (length > 8 * 1024 * 1024) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Asset exceeds 8 MiB"); return; }
    std::vector<uint8_t> data(length);
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(data.data()));
    report(env, reaktor_web_asset(handle, key.c_str(), type.c_str(), data.data(), length, policy.c_str()));
}
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_focus(JNIEnv *env, jobject, jlong handle) { report(env, reaktor_web_focus(handle)); }
extern "C" JNIEXPORT void JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_destroy(JNIEnv *env, jobject, jlong handle) { report(env, reaktor_web_destroy(handle)); }

