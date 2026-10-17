#import <Cocoa/Cocoa.h>
#include <jawt.h>
#include <jawt_md.h>
#include <dlfcn.h>
#include "jni_support.h"

namespace {
struct DynamicLibrary {
    void *handle;
    ~DynamicLibrary() { if (handle) dlclose(handle); }
};
NSView *view_for_layer(NSView *view, CALayer *target) {
    if ([view layer] == target) return view;
    for (NSView *child in [view subviews]) {
        if (auto found = view_for_layer(child, target)) return found;
    }
    return nil;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_shibasis_reaktor_web_NativeWebView_create(JNIEnv *env, jobject, jobject canvas, jboolean debug, jstring jawt_path, jstring requested_profile, jstring native_directory, jobject callback_object) {
    auto path = utf8(env, jawt_path);
    auto profile = utf8(env, requested_profile), directory = utf8(env, native_directory);
    DynamicLibrary library{dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL)};
    auto get_awt = library.handle ? reinterpret_cast<jboolean (*)(JNIEnv *, JAWT *)>(dlsym(library.handle, "JAWT_GetAWT")) : nullptr;
    JAWT awt{};
    awt.version = JAWT_VERSION_1_7;
    if (!get_awt || !get_awt(env, &awt)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "JAWT is unavailable in this JVM");
        return 0;
    }
    auto surface = awt.GetDrawingSurface(env, canvas);
    if (!surface) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "WebView requires a displayable AWT component");
        return 0;
    }
    CALayer *layer = nil;
    auto lock = surface->Lock(surface);
    if (!(lock & JAWT_LOCK_ERROR)) {
        auto info = surface->GetDrawingSurfaceInfo(surface);
        if (info && info->platformInfo) {
            auto layers = static_cast<id<JAWT_SurfaceLayers>>(info->platformInfo);
            layer = [[layers windowLayer] retain];
        }
        if (info) surface->FreeDrawingSurfaceInfo(info);
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    auto callbacks = make_callbacks(env, callback_object);
    if (env->ExceptionCheck()) { callback_release(callbacks.context); return 0; }
    __block reaktor_web_handle handle = 0;
    __block int status = REAKTOR_WEB_PLATFORM_ERROR;
    __block std::string error;
    dispatch_sync(dispatch_get_main_queue(), ^{
        NSView *parent = nil;
        for (NSWindow *window in [NSApp windows]) {
            if (layer && view_for_layer([window contentView], layer)) {
                // Kotlin measures bounds in window-content coordinates. JAWT
                // may identify a nested Canvas view (especially in a packaged
                // JVM), whose bounds and clipping differ from that coordinate
                // space. Use the matched window's content view as the host.
                parent = [window contentView];
                break;
            }
        }
        if (parent) {
            status = reaktor_web_create(parent, profile.c_str(), debug, directory.c_str(), callbacks, &handle);
            error = reaktor_web_last_error();
        } else {
            error = "Cannot resolve the AWT content NSView through JAWT; this JVM needs an embedding adapter";
        }
        [layer release];
    });
    if (status != REAKTOR_WEB_OK) callback_release(callbacks.context);
    if (status != REAKTOR_WEB_OK) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.c_str());
    return static_cast<jlong>(handle);
}
