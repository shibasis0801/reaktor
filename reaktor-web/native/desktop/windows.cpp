#include <windows.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <objidl.h>
#include <WebView2.h>
#include <webview/api.h>
#include "native_util.h"
#include "reaktor_hooks.h"
#include <functional>
#include <future>
#include <thread>
#include <mutex>
#include <memory>
#include <atomic>

namespace {
std::wstring wide(const std::string &text) {
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.c_str(), static_cast<int>(text.size()), nullptr, 0);
    if (!length && !text.empty()) throw std::runtime_error("Invalid UTF-8");
    std::wstring result(length, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()), result.data(), length);
    return result;
}
std::string narrow(const wchar_t *text) {
    if (!text) return {};
    int length = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
    std::string result(length, '\0');
    WideCharToMultiByte(CP_UTF8, 0, text, -1, result.data(), length, nullptr, nullptr);
    if (!result.empty()) result.pop_back();
    return result;
}
template<class T> struct Com {
    T *value = nullptr;
    ~Com() { if (value) value->Release(); }
    T *operator->() const { return value; }
    T **out() { return &value; }
};
void check(HRESULT status) { if (FAILED(status)) throw std::runtime_error("WebView2 error " + std::to_string(status)); }
template<class Interface, class... Args> class Callback final : public Interface {
    std::atomic<ULONG> references{1};
    std::function<HRESULT(Args...)> action;
public:
    explicit Callback(std::function<HRESULT(Args...)> block) : action(std::move(block)) {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override {
        if (iid == __uuidof(IUnknown) || iid == __uuidof(Interface)) { *object = static_cast<Interface *>(this); AddRef(); return S_OK; }
        *object = nullptr; return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references; }
    ULONG STDMETHODCALLTYPE Release() override { auto count = --references; if (!count) delete this; return count; }
    HRESULT STDMETHODCALLTYPE Invoke(Args... args) override { try { return action(args...); } catch (...) { return E_FAIL; } }
};
struct Session {
    reaktor_web_handle handle = 0;
    webview_t browser = nullptr;
    HWND child = nullptr;
    Com<ICoreWebView2Controller> controller;
    Com<ICoreWebView2> view;
    reaktor_web_callbacks callbacks{};
    NativeAssets assets;
    std::string profile, bundle_origin;
    std::wstring private_name;
    std::wstring data;
    ~Session() {
        if (controller.value) controller->Close();
        if (browser) webview_destroy(browser);
        if (child) DestroyWindow(child);
        if (callbacks.release) callbacks.release(callbacks.context);
    }
};
auto &sessions = *new std::map<reaktor_web_handle, std::unique_ptr<Session>>;
reaktor_web_handle next_handle = 1;
thread_local Session *constructing = nullptr;
thread_local std::string last_error;
std::once_flag initialized;
DWORD ui_thread = 0;
constexpr UINT command_message = WM_APP + 217;
Session *lookup(reaktor_web_handle h) { auto found = sessions.find(h); return found == sessions.end() ? nullptr : found->second.get(); }
void emit(Session &s, const std::string &body) { if (s.callbacks.event) s.callbacks.event(s.callbacks.context, body.c_str()); }
bool allows(Session &s, const std::string &url) { return s.callbacks.allows_navigation && s.callbacks.allows_navigation(s.callbacks.context, url.c_str()); }
std::string source(Session &s) { LPWSTR text = nullptr; check(s.view->get_Source(&text)); auto result = narrow(text); CoTaskMemFree(text); return result; }
void commit(Session &s) {
    LPWSTR title = nullptr; BOOL back = FALSE, forward = FALSE;
    s.view->get_DocumentTitle(&title); s.view->get_CanGoBack(&back); s.view->get_CanGoForward(&forward);
    emit(s, "{\"kind\":\"commit\",\"url\":" + quote(source(s)) + ",\"title\":" + quote(narrow(title)) +
        ",\"back\":" + (back ? "true" : "false") + ",\"forward\":" + (forward ? "true" : "false") + "}");
    CoTaskMemFree(title);
}
void start_ui() {
    std::call_once(initialized, [] {
        auto ready = std::make_shared<std::promise<HRESULT>>(); auto future = ready->get_future();
        std::thread([ready] {
            ui_thread = GetCurrentThreadId();
            HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
            MSG message; PeekMessageW(&message, nullptr, 0, 0, PM_NOREMOVE);
            ready->set_value(initialized);
            if (FAILED(initialized)) return;
            while (GetMessageW(&message, nullptr, 0, 0) > 0) {
                if (!message.hwnd && message.message == command_message) {
                    std::unique_ptr<std::function<void()>> action(reinterpret_cast<std::function<void()> *>(message.lParam));
                    (*action)();
                } else { TranslateMessage(&message); DispatchMessageW(&message); }
            }
            CoUninitialize();
        }).detach();
        check(future.get());
    });
}
int on_ui(const std::function<int()> &action) {
    try {
        start_ui();
        auto run = [&]() -> std::pair<int, std::string> { try { return {action(), ""}; } catch (const std::exception &e) { return {REAKTOR_WEB_PLATFORM_ERROR, e.what()}; } };
        std::pair<int, std::string> result;
        if (GetCurrentThreadId() == ui_thread) result = run();
        else {
            auto promise = std::make_shared<std::promise<std::pair<int, std::string>>>(); auto future = promise->get_future();
            auto task = new std::function<void()>([promise, run] { promise->set_value(run()); });
            if (!PostThreadMessageW(ui_thread, command_message, 0, reinterpret_cast<LPARAM>(task))) { delete task; throw std::runtime_error("Cannot dispatch to WebView2 thread"); }
            result = future.get();
        }
        last_error = result.second;
        if (result.first == REAKTOR_WEB_CLOSED) last_error = "WebView handle is closed";
        return result.first;
    } catch (const std::exception &e) { last_error = e.what(); return REAKTOR_WEB_PLATFORM_ERROR; }
}
int with_session(reaktor_web_handle h, const std::function<void(Session &)> &action) {
    return on_ui([&] { auto s = lookup(h); if (!s) return REAKTOR_WEB_CLOSED; action(*s); return REAKTOR_WEB_OK; });
}
template<class Interface, class Args, class Add> void listen(Session &s, Add add, std::function<HRESULT(Session &, Args *)> block) {
    auto callback = new Callback<Interface, ICoreWebView2 *, Args *>([handle=s.handle, block](ICoreWebView2 *, Args *args) {
        auto current = lookup(handle); return current ? block(*current, args) : S_OK;
    });
    EventRegistrationToken token{};
    HRESULT status = add(callback, &token);
    callback->Release();
    check(status);
}
}
long reaktor_web_create_windows_controller(void *environment, void *window, void *callback) {
    auto env = static_cast<ICoreWebView2Environment *>(environment);
    Com<ICoreWebView2Environment10> modern;
    HRESULT status = env->QueryInterface(__uuidof(ICoreWebView2Environment10), reinterpret_cast<void **>(modern.out()));
    if (FAILED(status) || !constructing) return E_NOINTERFACE;
    Com<ICoreWebView2ControllerOptions> options;
    status = modern->CreateCoreWebView2ControllerOptions(options.out());
    if (FAILED(status)) return status;
    options->put_IsInPrivateModeEnabled(constructing->profile == "ephemeral");
    auto name = constructing->profile == "ephemeral" ? constructing->private_name : wide(constructing->profile == "default" ? "Reaktor" : constructing->profile);
    options->put_ProfileName(name.c_str());
    return modern->CreateCoreWebView2ControllerWithOptions(static_cast<HWND>(window), options.value,
        static_cast<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler *>(callback));
}
const wchar_t *reaktor_web_windows_data_folder() { return constructing ? constructing->data.c_str() : nullptr; }
uint32_t reaktor_web_abi_version() { return REAKTOR_WEB_ABI_VERSION; }
uint32_t reaktor_web_features() { return 0x3ff; }
int reaktor_web_create(void *parent, const char *profile, int debug, const char *, reaktor_web_callbacks callbacks, reaktor_web_handle *result) {
    if (result) *result = 0;
    return on_ui([&] {
        if (!parent || !profile || !result || !IsWindow(static_cast<HWND>(parent))) return REAKTOR_WEB_INVALID_ARGUMENT;
        auto s = std::make_unique<Session>(); s->handle = next_handle++; s->profile = profile;
        PWSTR folder = nullptr; check(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &folder));
        GUID private_id; CoCreateGuid(&private_id); wchar_t private_text[40]; StringFromGUID2(private_id, private_text, 40); s->private_name = private_text;
        s->data = std::wstring(folder) + L"\\Reaktor\\WebView2"; CoTaskMemFree(folder);
        s->child = CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_CLIPCHILDREN | WS_CLIPSIBLINGS,
            0, 0, 1, 1, static_cast<HWND>(parent), nullptr, GetModuleHandleW(nullptr), nullptr);
        if (!s->child) throw std::runtime_error("Cannot create child HWND");
        constructing = s.get();
        s->browser = webview_create(debug, s->child);
        constructing = nullptr;
        if (!s->browser) throw std::runtime_error("WebView2 Runtime missing or required profile API unavailable");
        auto controller = static_cast<ICoreWebView2Controller *>(webview_get_native_handle(s->browser, WEBVIEW_NATIVE_HANDLE_KIND_BROWSER_CONTROLLER));
        controller->AddRef(); s->controller.value = controller;
        check(controller->get_CoreWebView2(s->view.out()));
        Com<ICoreWebView2Settings> settings; check(s->view->get_Settings(settings.out()));
        settings->put_AreDevToolsEnabled(debug); settings->put_AreDefaultScriptDialogsEnabled(FALSE);
        settings->put_IsStatusBarEnabled(FALSE);
        auto handle = s->handle; sessions.emplace(handle, std::move(s)); auto &created = *lookup(handle);
        try {
        // All event callbacks use the handle registry, never a freed session pointer.
        listen<ICoreWebView2NavigationStartingEventHandler, ICoreWebView2NavigationStartingEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_NavigationStarting(cb, token); },
            [](Session &s, auto args) {
                LPWSTR url = nullptr; args->get_Uri(&url); auto text = narrow(url); CoTaskMemFree(url);
                if (!allows(s, text)) { args->put_Cancel(TRUE); return S_OK; }
                emit(s, "{\"kind\":\"start\",\"url\":" + quote(text) + "}"); return S_OK;
            });
        listen<ICoreWebView2NavigationCompletedEventHandler, ICoreWebView2NavigationCompletedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_NavigationCompleted(cb, token); },
            [](Session &s, auto args) {
                BOOL success = FALSE; args->get_IsSuccess(&success); commit(s);
                emit(s, std::string("{\"kind\":\"finish\",\"error\":") + (success ? "\"\"" : "\"Navigation failed\"") + "}"); return S_OK;
            });
        listen<ICoreWebView2SourceChangedEventHandler, ICoreWebView2SourceChangedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_SourceChanged(cb, token); },
            [](Session &s, auto) { commit(s); return S_OK; });
        listen<ICoreWebView2WebMessageReceivedEventHandler, ICoreWebView2WebMessageReceivedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_WebMessageReceived(cb, token); },
            [](Session &s, auto args) {
                LPWSTR origin = nullptr, body = nullptr;
                args->get_Source(&origin); args->TryGetWebMessageAsString(&body);
                auto url = narrow(origin), text = narrow(body); CoTaskMemFree(origin); CoTaskMemFree(body);
                // This event is for the top-level CoreWebView2; frame messages use a separate API.
                if (!s.bundle_origin.empty() && url.rfind(s.bundle_origin + "/", 0) == 0 && allows(s, url) && text.size() <= 65536)
                    emit(s, "{\"kind\":\"message\",\"url\":" + quote(url) + ",\"main\":true,\"body\":" + quote(text) + "}");
                return S_OK;
            });
        listen<ICoreWebView2PermissionRequestedEventHandler, ICoreWebView2PermissionRequestedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_PermissionRequested(cb, token); },
            [](Session &, auto args) { args->put_State(COREWEBVIEW2_PERMISSION_STATE_DENY); return S_OK; });
        listen<ICoreWebView2NewWindowRequestedEventHandler, ICoreWebView2NewWindowRequestedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_NewWindowRequested(cb, token); },
            [](Session &, auto args) { args->put_Handled(TRUE); return S_OK; });
        listen<ICoreWebView2ProcessFailedEventHandler, ICoreWebView2ProcessFailedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_ProcessFailed(cb, token); },
            [](Session &s, auto) { emit(s, "{\"kind\":\"terminated\"}"); return S_OK; });
        Com<ICoreWebView2_4> downloads;
        if (SUCCEEDED(created.view->QueryInterface(__uuidof(ICoreWebView2_4), reinterpret_cast<void **>(downloads.out())))) {
            auto cb = new Callback<ICoreWebView2DownloadStartingEventHandler, ICoreWebView2 *, ICoreWebView2DownloadStartingEventArgs *>(
                [handle](ICoreWebView2 *, auto args) {
                    args->put_Cancel(TRUE); args->put_Handled(TRUE);
                    if (auto s = lookup(handle)) {
                        Com<ICoreWebView2DownloadOperation> download; args->get_DownloadOperation(download.out());
                        LPWSTR url = nullptr, mime = nullptr, name = nullptr;
                        download->get_Uri(&url); download->get_MimeType(&mime); args->get_ResultFilePath(&name);
                        emit(*s, "{\"kind\":\"download\",\"url\":" + quote(narrow(url)) + ",\"mime\":" + quote(narrow(mime)) + ",\"name\":" + quote(narrow(name)) + "}");
                        CoTaskMemFree(url); CoTaskMemFree(mime); CoTaskMemFree(name);
                    }
                    return S_OK;
                });
            EventRegistrationToken token{}; check(downloads->add_DownloadStarting(cb, &token)); cb->Release();
        }
        listen<ICoreWebView2WebResourceRequestedEventHandler, ICoreWebView2WebResourceRequestedEventArgs>(created,
            [&](auto cb, auto token) { return created.view->add_WebResourceRequested(cb, token); },
            [](Session &s, auto args) {
                Com<ICoreWebView2WebResourceRequest> request; args->get_Request(request.out());
                LPWSTR address = nullptr; request->get_Uri(&address); auto url = narrow(address); CoTaskMemFree(address);
                if (s.bundle_origin.empty() || url.rfind(s.bundle_origin + "/", 0) != 0) return S_OK;
                auto end = url.find_first_of("?#");
                auto path = asset_path(url.substr(s.bundle_origin.size(), end - s.bundle_origin.size()));
                auto found = s.assets.entries.find(path);
                LPWSTR method = nullptr; request->get_Method(&method); bool get = method && !wcscmp(method, L"GET"); CoTaskMemFree(method);
                bool exists = get && found != s.assets.entries.end();
                IStream *stream = SHCreateMemStream(exists ? found->second.bytes.data() : nullptr, exists ? static_cast<UINT>(found->second.bytes.size()) : 0);
                Com<ICoreWebView2Environment> environment;
                Com<ICoreWebView2_2> modern; check(s.view->QueryInterface(__uuidof(ICoreWebView2_2), reinterpret_cast<void **>(modern.out())));
                check(modern->get_Environment(environment.out()));
                std::string headers = exists ? "Content-Type: " + found->second.mime + "\r\nContent-Security-Policy: " + found->second.csp + "\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" : "Content-Type: text/plain\r\n";
                Com<ICoreWebView2WebResourceResponse> response;
                check(environment->CreateWebResourceResponse(stream, exists ? 200 : 404, exists ? L"OK" : L"Not Found", wide(headers).c_str(), response.out()));
                if (stream) stream->Release(); check(args->put_Response(response.value)); return S_OK;
            });
        check(created.view->AddWebResourceRequestedFilter(L"https://*.reaktor.invalid/*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL));
        check(created.view->AddScriptToExecuteOnDocumentCreated(
            L"window.__reaktorNativePost = body => window.chrome.webview.postMessage(body)", nullptr));
        } catch (...) { sessions.erase(handle); throw; }
        created.callbacks = callbacks;
        *result = handle; return REAKTOR_WEB_OK;
    });
}
int reaktor_web_bounds(reaktor_web_handle h, double, double, double w, double height, int visible) {
    return with_session(h, [&](Session &s) {
        // Parent is the AWT Canvas HWND; bounds are local to it.
        SetWindowPos(s.child, nullptr, 0, 0, static_cast<int>(w), static_cast<int>(height), SWP_NOZORDER | SWP_NOACTIVATE);
        ShowWindow(s.child, visible && w > 0 && height > 0 ? SW_SHOWNA : SW_HIDE);
        RECT bounds{0, 0, static_cast<LONG>(w), static_cast<LONG>(height)};
        s.controller->put_Bounds(bounds); s.controller->put_IsVisible(visible);
        s.controller->NotifyParentWindowPositionChanged();
    });
}
int reaktor_web_load_url(reaktor_web_handle h, const char *address) {
    return with_session(h, [&](Session &s) {
        std::string url = address;
        if (url.rfind("reaktor-app://", 0) == 0) {
            auto slash = url.find('/', 14);
            auto id = url.substr(14, slash - 14);
            s.bundle_origin = "https://" + id + ".reaktor.invalid";
            url = s.bundle_origin + url.substr(slash);
        } else s.bundle_origin.clear();
        check(s.view->Navigate(wide(url).c_str()));
    });
}
int reaktor_web_load_html(reaktor_web_handle h, const char *html) { return with_session(h, [&](Session &s) { s.bundle_origin.clear(); check(s.view->NavigateToString(wide(html).c_str())); }); }
int reaktor_web_execute(reaktor_web_handle h, const char *script) { return with_session(h, [&](Session &s) { check(s.view->ExecuteScript(wide(script).c_str(), nullptr)); }); }
int reaktor_web_evaluate(reaktor_web_handle h, const char *id, const char *script) {
    return with_session(h, [&](Session &s) {
        auto callback = new Callback<ICoreWebView2ExecuteScriptCompletedHandler, HRESULT, LPCWSTR>([h, request=std::string(id)](HRESULT status, LPCWSTR result) {
            if (auto s = lookup(h)) emit(*s, "{\"kind\":\"evaluation\",\"id\":" + quote(request) + ",\"value\":" +
                (SUCCEEDED(status) && result ? narrow(result) : "null") + ",\"error\":" + quote(FAILED(status) ? "JavaScript evaluation failed" : "") + "}");
            return S_OK;
        });
        HRESULT status = s.view->ExecuteScript(wide(script).c_str(), callback); callback->Release(); check(status);
    });
}
int reaktor_web_control(reaktor_web_handle h, int operation, double value) {
    return with_session(h, [&](Session &s) {
        switch (operation) {
            case 0: check(s.view->Reload()); break;
            case 1: check(s.view->GoBack()); break;
            case 2: check(s.view->GoForward()); break;
            case 3: check(s.controller->put_ZoomFactor(value)); break;
            case 4: check(s.view->OpenDevToolsWindow()); break;
            default: throw std::runtime_error("Unknown control");
        }
    });
}
int reaktor_web_clear_assets(reaktor_web_handle h) { return with_session(h, [](Session &s) { s.assets.clear(); }); }
int reaktor_web_asset(reaktor_web_handle h, const char *path, const char *mime, const uint8_t *bytes, uint32_t length, const char *csp) {
    return with_session(h, [&](Session &s) { s.assets.put(path, mime, bytes, length, csp); });
}
int reaktor_web_focus(reaktor_web_handle h) { return with_session(h, [](Session &s) { check(s.controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC)); }); }
int reaktor_web_destroy(reaktor_web_handle h) { return on_ui([&] { sessions.erase(h); return REAKTOR_WEB_OK; }); }
const char *reaktor_web_last_error() { return last_error.c_str(); }
