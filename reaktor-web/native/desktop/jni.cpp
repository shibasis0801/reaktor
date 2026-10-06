#include "jni_support.h"
#include <jawt.h>
#include <jawt_md.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

extern "C" JNIEXPORT jlong JNICALL Java_dev_shibasis_reaktor_web_NativeWebView_create(
    JNIEnv *env, jobject, jobject canvas, jboolean debug, jstring jawt_path, jstring requested_profile, jstring native_directory, jobject callback_object) {
    auto path = utf8(env, jawt_path), profile = utf8(env, requested_profile), directory = utf8(env, native_directory);
#ifdef _WIN32
    auto library = LoadLibraryA(path.c_str());
    auto get_awt = library ? reinterpret_cast<jboolean (JNICALL *)(JNIEnv *, JAWT *)>(GetProcAddress(library, "JAWT_GetAWT")) : nullptr;
#else
    auto library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    auto get_awt = library ? reinterpret_cast<jboolean (*)(JNIEnv *, JAWT *)>(dlsym(library, "JAWT_GetAWT")) : nullptr;
#endif
    JAWT awt{}; awt.version = JAWT_VERSION_1_4;
    void *parent = nullptr;
    if (get_awt && get_awt(env, &awt)) {
        auto surface = awt.GetDrawingSurface(env, canvas);
        if (surface) {
            if (!(surface->Lock(surface) & JAWT_LOCK_ERROR)) {
                auto info = surface->GetDrawingSurfaceInfo(surface);
                if (info && info->platformInfo) {
#ifdef _WIN32
                    parent = static_cast<JAWT_Win32DrawingSurfaceInfo *>(info->platformInfo)->hwnd;
#else
                    parent = reinterpret_cast<void *>(static_cast<JAWT_X11DrawingSurfaceInfo *>(info->platformInfo)->drawable);
#endif
                }
                if (info) surface->FreeDrawingSurfaceInfo(info);
                surface->Unlock(surface);
            }
            awt.FreeDrawingSurface(surface);
        }
    }
#ifdef _WIN32
    if (library) FreeLibrary(library);
#else
    if (library) dlclose(library);
#endif
    if (!parent) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot resolve the live AWT native parent through JAWT"); return 0; }
    auto callbacks = make_callbacks(env, callback_object);
    if (env->ExceptionCheck()) { callback_release(callbacks.context); return 0; }
    reaktor_web_handle handle = 0;
    int status = reaktor_web_create(parent, profile.c_str(), debug, directory.c_str(), callbacks, &handle);
    if (status != REAKTOR_WEB_OK) callback_release(callbacks.context);
    report(env, status);
    return static_cast<jlong>(handle);
}
