#include <gtk/gtk.h>
#include <gtk/gtkx.h>
#include <gdk/gdkx.h>
#include <webkit2/webkit2.h>
#include <webview/api.h>
#include "reaktor_hooks.h"
#include "native_util.h"
#include <future>
#include <thread>
#include <mutex>
#include <memory>
#include <functional>
#include <cstring>

namespace {
struct Session {
    reaktor_web_handle handle = 0;
    webview_t browser = nullptr;
    GtkWidget *staging = nullptr, *plug = nullptr;
    WebKitWebView *view = nullptr;
    reaktor_web_callbacks callbacks{};
    NativeAssets assets;
    std::string profile, directory;
    ~Session() {
        if (view) { g_signal_handlers_disconnect_by_data(view, this); webkit_web_view_stop_loading(view); }
        if (browser) webview_destroy(browser);
        if (plug) gtk_widget_destroy(plug);
        if (staging) gtk_widget_destroy(staging);
        if (callbacks.release) callbacks.release(callbacks.context);
    }
};
auto &sessions = *new std::map<reaktor_web_handle, std::unique_ptr<Session>>;
reaktor_web_handle next_handle = 1;
thread_local Session *constructing = nullptr;
thread_local std::string last_error;
std::once_flag initialized;
GMainContext *ui_context = nullptr;
std::thread::id ui_thread;
std::string initialization_error;
Session *lookup(reaktor_web_handle handle) { auto found = sessions.find(handle); return found == sessions.end() ? nullptr : found->second.get(); }
void emit(Session &s, const std::string &body) { if (s.callbacks.event) s.callbacks.event(s.callbacks.context, body.c_str()); }
const char *uri(Session &s) { auto value = webkit_web_view_get_uri(s.view); return value ? value : "about:blank"; }
bool allows(Session &s, const char *url) { return s.callbacks.allows_navigation && s.callbacks.allows_navigation(s.callbacks.context, url); }
void commit(Session &s) {
    auto title = webkit_web_view_get_title(s.view);
    emit(s, "{\"kind\":\"commit\",\"url\":" + quote(uri(s)) + ",\"title\":" + quote(title ? title : "") +
        ",\"back\":" + (webkit_web_view_can_go_back(s.view) ? "true" : "false") +
        ",\"forward\":" + (webkit_web_view_can_go_forward(s.view) ? "true" : "false") + "}");
}
void start_ui() {
    std::call_once(initialized, [] {
        auto ready = std::make_shared<std::promise<void>>();
        auto future = ready->get_future();
        std::thread([ready] {
            ui_thread = std::this_thread::get_id();
            if (!gtk_init_check(nullptr, nullptr)) { initialization_error = "GTK display unavailable"; ready->set_value(); return; }
            if (!GDK_IS_X11_DISPLAY(gdk_display_get_default())) { initialization_error = "AWT native embedding requires X11; Wayland is unavailable"; ready->set_value(); return; }
            ui_context = g_main_context_default();
            ready->set_value();
            gtk_main();
        }).detach();
        future.get();
    });
    if (!initialization_error.empty()) throw std::runtime_error(initialization_error);
}
int on_ui(const std::function<int()> &action) {
    try {
        start_ui();
        auto run = [&]() -> std::pair<int, std::string> {
            try { return {action(), ""}; } catch (const std::exception &e) { return {REAKTOR_WEB_PLATFORM_ERROR, e.what()}; }
        };
        std::pair<int, std::string> result;
        if (std::this_thread::get_id() == ui_thread) result = run();
        else {
            auto promise = std::make_shared<std::promise<std::pair<int, std::string>>>();
            auto future = promise->get_future();
            auto task = new std::function<void()>([promise, run] { promise->set_value(run()); });
            g_main_context_invoke(ui_context, [](gpointer data) -> gboolean {
                std::unique_ptr<std::function<void()>> task(static_cast<std::function<void()> *>(data));
                (*task)(); return G_SOURCE_REMOVE;
            }, task);
            result = future.get();
        }
        last_error = result.second;
        if (result.first == REAKTOR_WEB_CLOSED) last_error = "WebView handle is closed";
        return result.first;
    } catch (const std::exception &e) { last_error = e.what(); return REAKTOR_WEB_PLATFORM_ERROR; }
}
int with_session(reaktor_web_handle handle, const std::function<void(Session &)> &action) {
    return on_ui([&] { auto s = lookup(handle); if (!s) return REAKTOR_WEB_CLOSED; action(*s); return REAKTOR_WEB_OK; });
}
void scheme(WebKitURISchemeRequest *request, gpointer data) {
    auto s = lookup(reinterpret_cast<uintptr_t>(data));
    char *decoded = g_uri_unescape_string(webkit_uri_scheme_request_get_path(request), nullptr);
    std::string path = decoded ? asset_path(decoded) : "";
    g_free(decoded);
    auto found = s ? s->assets.entries.find(path) : std::map<std::string, NativeAsset>::iterator{};
    if (!s || found == s->assets.entries.end() || strcmp(webkit_uri_scheme_request_get_http_method(request), "GET")) {
        auto error = g_error_new_literal(G_IO_ERROR, G_IO_ERROR_NOT_FOUND, "Bundle asset unavailable");
        webkit_uri_scheme_request_finish_error(request, error); g_error_free(error); return;
    }
    const auto &asset = found->second;
    // Copy bytes into the response; replacing a bundle cannot invalidate an in-flight request.
    auto bytes = g_bytes_new(asset.bytes.data(), asset.bytes.size());
    auto stream = g_memory_input_stream_new_from_bytes(bytes);
    auto response = webkit_uri_scheme_response_new(stream, asset.bytes.size());
    webkit_uri_scheme_response_set_content_type(response, asset.mime.c_str());
    auto headers = soup_message_headers_new(SOUP_MESSAGE_HEADERS_RESPONSE);
    soup_message_headers_append(headers, "Content-Security-Policy", asset.csp.c_str());
    soup_message_headers_append(headers, "X-Content-Type-Options", "nosniff");
    soup_message_headers_append(headers, "Referrer-Policy", "no-referrer");
    soup_message_headers_append(headers, "Permissions-Policy", "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()");
    webkit_uri_scheme_response_set_http_headers(response, headers);
    webkit_uri_scheme_request_finish_with_response(request, response);
    g_object_unref(response); g_object_unref(stream); g_bytes_unref(bytes);
}
}
void *reaktor_web_create_gtk() {
    auto s = constructing;
    if (!s) return webkit_web_view_new();
    WebKitWebsiteDataManager *manager;
    if (s->profile == "ephemeral") manager = webkit_website_data_manager_new_ephemeral();
    else {
        std::string root = std::string(g_get_user_data_dir()) + "/reaktor/web/" + s->profile;
        manager = webkit_website_data_manager_new("base-data-directory", (root + "/data").c_str(),
            "base-cache-directory", (root + "/cache").c_str(), nullptr);
    }
    auto context = webkit_web_context_new_with_website_data_manager(manager);
    webkit_web_context_set_web_extensions_directory(context, s->directory.c_str());
    webkit_web_context_register_uri_scheme(context, "reaktor-app", scheme, reinterpret_cast<gpointer>(s->handle), nullptr);
    auto security = webkit_web_context_get_security_manager(context);
    webkit_security_manager_register_uri_scheme_as_secure(security, "reaktor-app");
    webkit_security_manager_register_uri_scheme_as_cors_enabled(security, "reaktor-app");
    auto widget = g_object_new(WEBKIT_TYPE_WEB_VIEW, "web-context", context, nullptr);
    g_object_unref(context); g_object_unref(manager);
    return widget;
}
uint32_t reaktor_web_abi_version() { return REAKTOR_WEB_ABI_VERSION; }
uint32_t reaktor_web_features() { return 0x3ff; }
int reaktor_web_create(void *parent, const char *profile, int debug, const char *directory, reaktor_web_callbacks callbacks, reaktor_web_handle *result) {
    if (result) *result = 0;
    return on_ui([&] {
        if (!parent || !profile || !directory || !result) return REAKTOR_WEB_INVALID_ARGUMENT;
        auto s = std::make_unique<Session>();
        s->handle = next_handle++; s->profile = profile; s->directory = directory;
        s->staging = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        constructing = s.get();
        s->browser = webview_create(debug, s->staging);
        constructing = nullptr;
        if (!s->browser) throw std::runtime_error("webview/webview could not create WebKitGTK");
        s->view = WEBKIT_WEB_VIEW(webview_get_native_handle(s->browser, WEBVIEW_NATIVE_HANDLE_KIND_UI_WIDGET));
        auto widget = GTK_WIDGET(s->view);
        g_object_ref(widget);
        gtk_container_remove(GTK_CONTAINER(gtk_widget_get_parent(widget)), widget);
        s->plug = gtk_plug_new(reinterpret_cast<uintptr_t>(parent));
        gtk_container_add(GTK_CONTAINER(s->plug), widget);
        g_object_unref(widget);
        gtk_widget_hide(s->staging);
        auto settings = webkit_web_view_get_settings(s->view);
        webkit_settings_set_javascript_can_access_clipboard(settings, FALSE);
        webkit_settings_set_javascript_can_open_windows_automatically(settings, FALSE);
        webkit_settings_set_allow_file_access_from_file_urls(settings, FALSE);
        webkit_settings_set_allow_universal_access_from_file_urls(settings, FALSE);
        g_signal_connect(s->view, "load-changed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent event, gpointer data) {
            auto &s = *static_cast<Session *>(data);
            if (event == WEBKIT_LOAD_STARTED) emit(s, "{\"kind\":\"start\",\"url\":" + quote(uri(s)) + "}");
            if (event == WEBKIT_LOAD_COMMITTED) commit(s);
            if (event == WEBKIT_LOAD_FINISHED) { commit(s); emit(s, "{\"kind\":\"finish\"}"); }
        }), s.get());
        g_signal_connect(s->view, "notify::uri", G_CALLBACK(+[](GObject *, GParamSpec *, gpointer data) { commit(*static_cast<Session *>(data)); }), s.get());
        g_signal_connect(s->view, "load-failed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent, const char *, GError *error, gpointer data) -> gboolean {
            emit(*static_cast<Session *>(data), "{\"kind\":\"finish\",\"error\":" + quote(error->message) + "}"); return TRUE;
        }), s.get());
        g_signal_connect(s->view, "decide-policy", G_CALLBACK(+[](WebKitWebView *, WebKitPolicyDecision *decision, WebKitPolicyDecisionType type, gpointer data) -> gboolean {
            auto &s = *static_cast<Session *>(data);
            if (type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) { webkit_policy_decision_ignore(decision); return TRUE; }
            if (type == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) {
                auto action = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(decision));
                auto request = webkit_navigation_action_get_request(action);
                if (!allows(s, webkit_uri_request_get_uri(request))) { webkit_policy_decision_ignore(decision); return TRUE; }
            }
            if (type == WEBKIT_POLICY_DECISION_TYPE_RESPONSE) {
                auto response = WEBKIT_RESPONSE_POLICY_DECISION(decision);
                if (!webkit_response_policy_decision_is_mime_type_supported(response)) {
                    auto info = webkit_response_policy_decision_get_response(response);
                    emit(s, "{\"kind\":\"download\",\"url\":" + quote(webkit_uri_response_get_uri(info)) +
                        ",\"mime\":" + quote(webkit_uri_response_get_mime_type(info)) + ",\"name\":" +
                        quote(webkit_uri_response_get_suggested_filename(info) ?: "download") + "}");
                    webkit_policy_decision_ignore(decision); return TRUE;
                }
            }
            return FALSE;
        }), s.get());
        g_signal_connect(s->view, "permission-request", G_CALLBACK(+[](WebKitWebView *, WebKitPermissionRequest *request, gpointer) -> gboolean {
            webkit_permission_request_deny(request); return TRUE;
        }), nullptr);
        g_signal_connect(s->view, "run-file-chooser", G_CALLBACK(+[](WebKitWebView *, WebKitFileChooserRequest *request, gpointer) -> gboolean {
            webkit_file_chooser_request_cancel(request); return TRUE;
        }), nullptr);
        g_signal_connect(s->view, "web-process-terminated", G_CALLBACK(+[](WebKitWebView *, WebKitWebProcessTerminationReason, gpointer data) {
            emit(*static_cast<Session *>(data), "{\"kind\":\"terminated\"}");
        }), s.get());
        g_signal_connect(s->view, "user-message-received", G_CALLBACK(+[](WebKitWebView *, WebKitUserMessage *message, gpointer data) -> gboolean {
            auto &s = *static_cast<Session *>(data);
            if (strcmp(webkit_user_message_get_name(message), "reaktor.frame.message")) return FALSE;
            auto args = webkit_user_message_get_parameters(message);
            if (!g_variant_is_of_type(args, G_VARIANT_TYPE("(sbs)"))) return TRUE;
            const char *url; const char *body; gboolean main;
            g_variant_get(args, "(&sb&s)", &url, &main, &body);
            if (main && strlen(body) <= 65536 && allows(s, url)) emit(s,
                "{\"kind\":\"message\",\"url\":" + quote(url) + ",\"main\":true,\"body\":" + quote(body) + "}");
            return TRUE;
        }), s.get());
        s->callbacks = callbacks;
        *result = s->handle;
        sessions.emplace(s->handle, std::move(s));
        return REAKTOR_WEB_OK;
    });
}
int reaktor_web_bounds(reaktor_web_handle h, double, double, double w, double height, int visible) {
    return with_session(h, [&](Session &s) {
        gtk_widget_set_size_request(GTK_WIDGET(s.view), static_cast<int>(w), static_cast<int>(height));
        gtk_window_resize(GTK_WINDOW(s.plug), std::max(1, static_cast<int>(w)), std::max(1, static_cast<int>(height)));
        if (visible && w > 0 && height > 0) gtk_widget_show_all(s.plug); else gtk_widget_hide(s.plug);
    });
}
int reaktor_web_load_url(reaktor_web_handle h, const char *url) { return with_session(h, [&](Session &s) { webkit_web_view_load_uri(s.view, url); }); }
int reaktor_web_load_html(reaktor_web_handle h, const char *html) { return with_session(h, [&](Session &s) { webkit_web_view_load_html(s.view, html, nullptr); }); }
int reaktor_web_execute(reaktor_web_handle h, const char *script) { return with_session(h, [&](Session &s) { webview_eval(s.browser, script); }); }
int reaktor_web_evaluate(reaktor_web_handle h, const char *id, const char *script) {
    return with_session(h, [&](Session &s) {
        auto request = new std::pair<reaktor_web_handle, std::string>(h, id);
        webkit_web_view_evaluate_javascript(s.view, script, -1, nullptr, nullptr, nullptr, +[](GObject *view, GAsyncResult *result, gpointer data) {
            std::unique_ptr<std::pair<reaktor_web_handle, std::string>> request(static_cast<std::pair<reaktor_web_handle, std::string> *>(data));
            GError *error = nullptr;
            auto value = webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(view), result, &error);
            char *json = value ? jsc_value_to_json(value, 0) : nullptr;
            if (auto s = lookup(request->first)) emit(*s, "{\"kind\":\"evaluation\",\"id\":" + quote(request->second) +
                ",\"value\":" + (json ? json : "null") + ",\"error\":" + quote(error ? error->message : "") + "}");
            g_free(json); if (value) g_object_unref(value); if (error) g_error_free(error);
        }, request);
    });
}
int reaktor_web_control(reaktor_web_handle h, int operation, double value) {
    return with_session(h, [&](Session &s) {
        switch (operation) {
            case 0: webkit_web_view_reload(s.view); break;
            case 1: webkit_web_view_go_back(s.view); break;
            case 2: webkit_web_view_go_forward(s.view); break;
            case 3: webkit_web_view_set_zoom_level(s.view, value); break;
            case 4: webkit_web_inspector_show(webkit_web_view_get_inspector(s.view)); break;
            default: throw std::runtime_error("Unknown control");
        }
    });
}
int reaktor_web_clear_assets(reaktor_web_handle h) { return with_session(h, [](Session &s) { s.assets.clear(); }); }
int reaktor_web_asset(reaktor_web_handle h, const char *path, const char *mime, const uint8_t *bytes, uint32_t length, const char *csp) {
    return with_session(h, [&](Session &s) { s.assets.put(path, mime, bytes, length, csp); });
}
int reaktor_web_focus(reaktor_web_handle h) { return with_session(h, [](Session &s) { gtk_widget_grab_focus(GTK_WIDGET(s.view)); }); }
int reaktor_web_destroy(reaktor_web_handle h) { return on_ui([&] { sessions.erase(h); return REAKTOR_WEB_OK; }); }
const char *reaktor_web_last_error() { return last_error.c_str(); }
