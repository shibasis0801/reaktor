#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define REAKTOR_WEB_API __declspec(dllexport)
#else
#define REAKTOR_WEB_API __attribute__((visibility("default")))
#endif
#define REAKTOR_WEB_ABI_VERSION 2
typedef uint64_t reaktor_web_handle;
enum reaktor_web_status {
    REAKTOR_WEB_OK = 0,
    REAKTOR_WEB_INVALID_ARGUMENT = 1,
    REAKTOR_WEB_PLATFORM_ERROR = 2,
    REAKTOR_WEB_CLOSED = 3
};

// NSView*, HWND or X11 Window ID. Caller owns the parent for the session lifetime.
// Callbacks are consumed on successful create and released exactly once on destroy.
// Events/authorization execute on the native UI thread; they must never wait for AWT.
typedef struct reaktor_web_callbacks {
    void *context;
    void (*event)(void *context, const char *json);
    int (*allows_navigation)(void *context, const char *url);
    void (*release)(void *context);
} reaktor_web_callbacks;
REAKTOR_WEB_API uint32_t reaktor_web_abi_version(void);
REAKTOR_WEB_API uint32_t reaktor_web_features(void);
REAKTOR_WEB_API int reaktor_web_create(void *parent_view, const char *profile, int debug, const char *native_directory,
    reaktor_web_callbacks callbacks, reaktor_web_handle *result);
REAKTOR_WEB_API int reaktor_web_bounds(reaktor_web_handle handle, double x, double y, double width, double height, int visible);
REAKTOR_WEB_API int reaktor_web_load_url(reaktor_web_handle handle, const char *url);
REAKTOR_WEB_API int reaktor_web_load_html(reaktor_web_handle handle, const char *html);
// Submission only; this API does not return a JavaScript evaluation result.
REAKTOR_WEB_API int reaktor_web_execute(reaktor_web_handle handle, const char *script);
REAKTOR_WEB_API int reaktor_web_evaluate(reaktor_web_handle handle, const char *id, const char *script);
// 0 reload, 1 back, 2 forward, 3 zoom, 4 open developer tools.
REAKTOR_WEB_API int reaktor_web_control(reaktor_web_handle handle, int operation, double value);
REAKTOR_WEB_API int reaktor_web_clear_assets(reaktor_web_handle handle);
REAKTOR_WEB_API int reaktor_web_asset(reaktor_web_handle handle, const char *path, const char *mime,
    const uint8_t *bytes, uint32_t length, const char *csp);
REAKTOR_WEB_API int reaktor_web_focus(reaktor_web_handle handle);
REAKTOR_WEB_API int reaktor_web_destroy(reaktor_web_handle handle);
// Valid until the next API call on this thread; never free the returned string.
REAKTOR_WEB_API const char *reaktor_web_last_error(void);

#ifdef __cplusplus
}
#endif
