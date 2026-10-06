#include <webkit2/webkit-web-extension.h>
#include <jsc/jsc.h>
#include <cstring>

struct Source { GWeakRef page, frame; };
static void release(gpointer data) {
    auto source = static_cast<Source *>(data);
    g_weak_ref_clear(&source->page);
    g_weak_ref_clear(&source->frame);
    delete source;
}
static void post(const char *body, gpointer data) {
    auto source = static_cast<Source *>(data);
    auto page = WEBKIT_WEB_PAGE(g_weak_ref_get(&source->page));
    auto frame = WEBKIT_FRAME(g_weak_ref_get(&source->frame));
    if (page && frame && body && strlen(body) <= 65536 && webkit_frame_is_main_frame(frame)) {
        const char *url = webkit_frame_get_uri(frame);
        if (url && g_str_has_prefix(url, "reaktor-app://")) {
            auto message = webkit_user_message_new("reaktor.frame.message",
                g_variant_new("(sbs)", url, TRUE, body));
            webkit_web_page_send_message_to_view(page, message, nullptr, nullptr, nullptr);
        }
    }
    if (page) g_object_unref(page);
    if (frame) g_object_unref(frame);
}
static void window_cleared(WebKitScriptWorld *, WebKitWebPage *page, WebKitFrame *frame, gpointer) {
    auto context = webkit_frame_get_js_context(frame);
    auto source = new Source;
    g_weak_ref_init(&source->page, page);
    g_weak_ref_init(&source->frame, frame);
    auto function = jsc_value_new_function(context, "__reaktorNativePost", G_CALLBACK(post), source, release,
        G_TYPE_NONE, 1, G_TYPE_STRING);
    jsc_context_set_value(context, "__reaktorNativePost", function);
    g_object_unref(function);
    g_object_unref(context);
}
extern "C" G_MODULE_EXPORT void webkit_web_extension_initialize(WebKitWebExtension *) {
    g_signal_connect(webkit_script_world_get_default(), "window-object-cleared", G_CALLBACK(window_cleared), nullptr);
}
