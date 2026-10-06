#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <webview/api.h>
#include "reaktor_web.h"
#include "reaktor_hooks.h"
#include <functional>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Asset { std::vector<uint8_t> bytes; std::string mime, csp; };
struct Session {
    reaktor_web_handle handle = 0;
    webview_t browser = nullptr;
    NSWindow *staging = nil;
    NSView *parent = nil;
    NSView *widget = nil;
    WKWebView *view = nil;
    id delegate = nil;
    id editing_monitor = nil;
    std::string profile;
    bool debug = false;
    bool observes_url = false;
    bool observes_title = false;
    reaktor_web_callbacks callbacks{};
    std::map<std::string, Asset> assets;
    size_t asset_bytes = 0;
    ~Session();
};
// Never destroy AppKit objects from the JVM's process-exit thread.
auto &sessions = *new std::map<reaktor_web_handle, std::unique_ptr<Session>>;
reaktor_web_handle next_handle = 1;
thread_local Session *constructing = nullptr;
thread_local std::string last_error;
Session *lookup(reaktor_web_handle id) {
    auto found = sessions.find(id);
    return found == sessions.end() ? nullptr : found->second.get();
}
NSString *ns(const char *text) { return [NSString stringWithUTF8String:text ? text : ""]; }
std::string string(NSString *text) { return text ? [text UTF8String] : ""; }
void emit(Session &session, NSDictionary *event) {
    if (!session.callbacks.event) return;
    NSData *data = [NSJSONSerialization dataWithJSONObject:event options:0 error:nil];
    if (!data || [data length] > 131072) return;
    std::string json(static_cast<const char *>([data bytes]), [data length]);
    session.callbacks.event(session.callbacks.context, json.c_str());
}
void committed(Session &session) {
    emit(session, @{@"kind":@"commit", @"url":[session.view.URL absoluteString] ?: @"about:blank",
        @"title":session.view.title ?: @"", @"back":@(session.view.canGoBack), @"forward":@(session.view.canGoForward)});
}
bool allows(Session &session, NSString *url) {
    return session.callbacks.allows_navigation && session.callbacks.allows_navigation(session.callbacks.context, [url UTF8String]);
}
int on_main(const std::function<int()> &action) {
    __block int status = REAKTOR_WEB_PLATFORM_ERROR;
    __block std::string error;
    void (^run)(void) = ^{
        @try {
            try { status = action(); }
            catch (const std::exception &exception) { error = exception.what(); }
            catch (...) { error = "Unknown native WebView error"; }
        } @catch (NSException *exception) { error = string(exception.reason); }
    };
    if ([NSThread isMainThread]) run(); else dispatch_sync(dispatch_get_main_queue(), run);
    if (status == REAKTOR_WEB_CLOSED) error = "WebView handle is closed";
    if (status == REAKTOR_WEB_INVALID_ARGUMENT) error = "Invalid WebView argument";
    last_error = error;
    return status;
}
int with_session(reaktor_web_handle handle, const std::function<void(Session &)> &action) {
    return on_main([&] {
        auto session = lookup(handle);
        if (!session) return REAKTOR_WEB_CLOSED;
        action(*session);
        return REAKTOR_WEB_OK;
    });
}
void check(webview_error_t status) {
    if (status < 0) throw std::runtime_error("webview/webview rejected operation: " + std::to_string(status));
}
}

@interface ReaktorWebDelegate : NSObject <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, WKURLSchemeHandler>
@property(nonatomic) reaktor_web_handle handle;
@end

@implementation ReaktorWebDelegate
- (void)observeValueForKeyPath:(NSString *)path ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    (void)path; (void)object; (void)change; (void)context;
    if (auto session = lookup(self.handle)) committed(*session);
}
- (void)webView:(WKWebView *)view decidePolicyForNavigationAction:(WKNavigationAction *)action
    decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    (void)view;
    auto session = lookup(self.handle);
    bool accepted = session && action.targetFrame && (!action.targetFrame.mainFrame ||
        allows(*session, action.request.URL.absoluteString ?: @""));
    decision(accepted ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
}
- (void)webView:(WKWebView *)view didStartProvisionalNavigation:(WKNavigation *)navigation {
    (void)navigation;
    if (auto session = lookup(self.handle)) emit(*session, @{@"kind":@"start", @"url":view.URL.absoluteString ?: @""});
}
- (void)webView:(WKWebView *)view didCommitNavigation:(WKNavigation *)navigation {
    (void)view; (void)navigation;
    if (auto session = lookup(self.handle)) committed(*session);
}
- (void)webView:(WKWebView *)view didFinishNavigation:(WKNavigation *)navigation {
    (void)view; (void)navigation;
    if (auto session = lookup(self.handle)) {
        committed(*session);
        emit(*session, @{@"kind":@"finish"});
    }
}
- (void)webView:(WKWebView *)view didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    (void)view; (void)navigation;
    if (auto session = lookup(self.handle)) emit(*session, @{@"kind":@"finish", @"error":error.localizedDescription ?: @"Navigation failed"});
}
- (void)webView:(WKWebView *)view didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    [self webView:view didFailProvisionalNavigation:navigation withError:error];
}
- (void)webViewWebContentProcessDidTerminate:(WKWebView *)view {
    (void)view;
    if (auto session = lookup(self.handle)) emit(*session, @{@"kind":@"terminated"});
}
- (void)webView:(WKWebView *)view decidePolicyForNavigationResponse:(WKNavigationResponse *)response
    decisionHandler:(void (^)(WKNavigationResponsePolicy))decision {
    (void)view;
    if (!response.canShowMIMEType) {
        if (auto session = lookup(self.handle)) emit(*session, @{@"kind":@"download",
            @"url":response.response.URL.absoluteString ?: @"", @"name":response.response.suggestedFilename ?: @"download",
            @"mime":response.response.MIMEType ?: @"application/octet-stream"});
        decision(WKNavigationResponsePolicyCancel);
    } else decision(WKNavigationResponsePolicyAllow);
}
- (WKWebView *)webView:(WKWebView *)view createWebViewWithConfiguration:(WKWebViewConfiguration *)config
    forNavigationAction:(WKNavigationAction *)action windowFeatures:(WKWindowFeatures *)features {
    (void)view; (void)config; (void)action; (void)features;
    return nil;
}
- (void)webView:(WKWebView *)view runOpenPanelWithParameters:(WKOpenPanelParameters *)parameters
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(NSArray<NSURL *> *))completion {
    (void)view; (void)parameters; (void)frame;
    completion(nil);
}
- (void)webView:(WKWebView *)view requestMediaCapturePermissionForOrigin:(WKSecurityOrigin *)origin
    initiatedByFrame:(WKFrameInfo *)frame type:(WKMediaCaptureType)type decisionHandler:(void (^)(WKPermissionDecision))decision {
    (void)view; (void)origin; (void)frame; (void)type;
    decision(WKPermissionDecisionDeny);
}
- (void)webView:(WKWebView *)view runJavaScriptAlertPanelWithMessage:(NSString *)message
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(void))completion {
    (void)view; (void)message; (void)frame; completion();
}
- (void)webView:(WKWebView *)view runJavaScriptConfirmPanelWithMessage:(NSString *)message
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(BOOL))completion {
    (void)view; (void)message; (void)frame; completion(NO);
}
- (void)webView:(WKWebView *)view runJavaScriptTextInputPanelWithPrompt:(NSString *)prompt defaultText:(NSString *)text
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(NSString *))completion {
    (void)view; (void)prompt; (void)text; (void)frame; completion(nil);
}
- (void)userContentController:(WKUserContentController *)controller didReceiveScriptMessage:(WKScriptMessage *)message {
    (void)controller;
    if (auto session = lookup(self.handle)) {
        if (![message.body isKindOfClass:[NSString class]] || !message.frameInfo.mainFrame) return;
        NSString *url = message.frameInfo.request.URL.absoluteString ?: @"";
        // Only bundle documents receive privilege; engine provenance is mandatory.
        if (![url hasPrefix:@"reaktor-app://"] || !allows(*session, url)) return;
        emit(*session, @{@"kind":@"message", @"body":message.body, @"url":url, @"main":@YES});
    }
}
- (void)webView:(WKWebView *)view startURLSchemeTask:(id<WKURLSchemeTask>)task {
    (void)view;
    auto session = lookup(self.handle);
    NSURL *url = task.request.URL;
    NSString *path = [url.path stringByRemovingPercentEncoding];
    NSArray *segments = [path componentsSeparatedByString:@"/"];
    std::string key;
    bool safe = segments.count >= 3 && [task.request.HTTPMethod isEqualToString:@"GET"];
    for (NSUInteger i = 2; i < segments.count; ++i) {
        NSString *part = segments[i];
        if (!part.length || [part isEqualToString:@"."] || [part isEqualToString:@".."] ||
            [part containsString:@"\\"] || [part containsString:@"%"]) safe = false;
        key += "/" + string(part);
    }
    auto found = session ? session->assets.find(key) : std::map<std::string, Asset>::iterator{};
    bool exists = safe && session && found != session->assets.end();
    NSDictionary *headers = exists ? @{
        @"Content-Type":ns(found->second.mime.c_str()), @"Content-Security-Policy":ns(found->second.csp.c_str()),
        @"X-Content-Type-Options":@"nosniff", @"Referrer-Policy":@"no-referrer",
        @"Permissions-Policy":@"camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()"
    } : @{@"Content-Type":@"text/plain"};
    NSHTTPURLResponse *response = [[[NSHTTPURLResponse alloc] initWithURL:url statusCode:exists ? 200 : 404
        HTTPVersion:@"HTTP/1.1" headerFields:headers] autorelease];
    [task didReceiveResponse:response];
    if (exists) [task didReceiveData:[NSData dataWithBytes:found->second.bytes.data() length:found->second.bytes.size()]];
    [task didFinish];
}
- (void)webView:(WKWebView *)view stopURLSchemeTask:(id<WKURLSchemeTask>)task { (void)view; (void)task; }
@end

namespace {
Session::~Session() {
    if (editing_monitor) { [NSEvent removeMonitor:editing_monitor]; [editing_monitor release]; }
    if (observes_url) [view removeObserver:delegate forKeyPath:@"URL"];
    if (observes_title) [view removeObserver:delegate forKeyPath:@"title"];
    [view stopLoading];
    view.navigationDelegate = nil;
    view.UIDelegate = nil;
    [view.configuration.userContentController removeScriptMessageHandlerForName:@"reaktor"];
    [widget removeFromSuperview];
    if (browser) webview_destroy(browser);
    [delegate release];
    [staging close];
    [staging release];
    [parent release];
    if (callbacks.release) callbacks.release(callbacks.context);
}
}
void reaktor_web_configure_apple(void *configuration) {
    auto session = constructing;
    if (!session) return;
    WKWebViewConfiguration *config = static_cast<WKWebViewConfiguration *>(configuration);
    if (session->profile == "ephemeral") config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
    else if (session->profile == "default") config.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
    else {
        if (@available(macOS 14.0, *)) {
            NSUUID *identifier = [[[NSUUID alloc] initWithUUIDString:ns(session->profile.c_str())] autorelease];
            if (!identifier) throw std::runtime_error("Invalid persistent profile UUID");
            config.websiteDataStore = [WKWebsiteDataStore dataStoreForIdentifier:identifier];
        } else throw std::runtime_error("Isolated persistent profiles require macOS 14");
    }
    // Upstream enables clipboard access. Reaktor denies ambient page access.
    [config.preferences setValue:@NO forKey:@"javaScriptCanAccessClipboard"];
    [config.preferences setValue:@NO forKey:@"DOMPasteAllowed"];
    config.preferences.javaScriptCanOpenWindowsAutomatically = NO;
    [config setURLSchemeHandler:session->delegate forURLScheme:@"reaktor-app"];
    [config.userContentController addScriptMessageHandler:session->delegate name:@"reaktor"];
    if (session->debug) {
        NSString *source = @"window.__reaktorHostErrors=[];window.addEventListener('error',e=>window.__reaktorHostErrors.push(e.message||e.target.src),true);window.addEventListener('unhandledrejection',e=>window.__reaktorHostErrors.push(String(e.reason)));";
        WKUserScript *script = [[[WKUserScript alloc] initWithSource:source injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:YES] autorelease];
        [config.userContentController addUserScript:script];
    }
}

uint32_t reaktor_web_abi_version() { return REAKTOR_WEB_ABI_VERSION; }
uint32_t reaktor_web_features() { return 0x3bf; } // all except public DevTools opening (bit 6)
int reaktor_web_create(void *parent, const char *profile, int debug, const char *, reaktor_web_callbacks callbacks, reaktor_web_handle *result) {
    if (result) *result = 0;
    return on_main([&] {
        if (!parent || !profile || !result || !callbacks.event || !callbacks.allows_navigation) return REAKTOR_WEB_INVALID_ARGUMENT;
        auto session = std::make_unique<Session>();
        session->handle = next_handle++;
        // AWT can dispose its window before queued native teardown executes.
        session->parent = [static_cast<NSView *>(parent) retain];
        session->profile = profile;
        session->debug = debug;
        if (![session->parent window]) throw std::runtime_error("Parent NSView must belong to a live window");
        ReaktorWebDelegate *delegate = [[ReaktorWebDelegate alloc] init];
        delegate.handle = session->handle;
        session->delegate = delegate;
        session->staging = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 1, 1)
            styleMask:NSWindowStyleMaskBorderless backing:NSBackingStoreBuffered defer:NO];
        [session->staging setReleasedWhenClosed:NO];
        constructing = session.get();
        try { session->browser = webview_create(0, session->staging); } catch (...) { constructing = nullptr; throw; }
        constructing = nullptr;
        if (!session->browser) throw std::runtime_error("webview/webview could not create WKWebView");
        session->widget = static_cast<NSView *>(webview_get_native_handle(session->browser, WEBVIEW_NATIVE_HANDLE_KIND_UI_WIDGET));
        auto browser_view = webview_get_native_handle(session->browser, WEBVIEW_NATIVE_HANDLE_KIND_BROWSER_CONTROLLER);
        if (!browser_view || ![static_cast<id>(browser_view) isKindOfClass:[WKWebView class]])
            throw std::runtime_error("webview/webview did not expose a WKWebView controller");
        session->view = static_cast<WKWebView *>(browser_view);
        session->view.navigationDelegate = delegate;
        session->view.UIDelegate = delegate;
        if (@available(macOS 13.3, *)) session->view.inspectable = debug;
        [session->widget retain];
        [session->staging setContentView:nil];
        session->widget.hidden = YES;
        [session->parent addSubview:session->widget];
        [session->widget release];
        [session->staging orderOut:nil];
        [session->view addObserver:delegate forKeyPath:@"URL" options:0 context:nullptr];
        session->observes_url = true;
        [session->view addObserver:delegate forKeyPath:@"title" options:0 context:nullptr];
        session->observes_title = true;
        const auto handle = session->handle;
        sessions.emplace(handle, std::move(session));
        lookup(handle)->callbacks = callbacks;
        // AWT owns the application's menu bar, so WKWebView's standard editing
        // key equivalents need routing to its native first responder. Scope the
        // monitor to this window and focused view; Compose keeps its shortcuts.
        lookup(handle)->editing_monitor = [[NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
            handler:^NSEvent *(NSEvent *event) {
                auto current = lookup(handle);
                if (!current || event.window != current->parent.window) return event;
                NSResponder *responder = event.window.firstResponder;
                if (![responder isKindOfClass:[NSView class]] ||
                    ![static_cast<NSView *>(responder) isDescendantOf:current->view]) return event;
                NSEventModifierFlags modifiers = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
                if (!(modifiers & NSEventModifierFlagCommand) ||
                    (modifiers & (NSEventModifierFlagControl | NSEventModifierFlagOption))) return event;
                NSString *key = event.charactersIgnoringModifiers.lowercaseString;
                SEL action = nullptr;
                if ([key isEqualToString:@"a"]) action = @selector(selectAll:);
                else if ([key isEqualToString:@"c"]) action = @selector(copy:);
                else if ([key isEqualToString:@"x"]) action = @selector(cut:);
                else if ([key isEqualToString:@"v"]) action = @selector(paste:);
                else if ([key isEqualToString:@"z"]) action = (modifiers & NSEventModifierFlagShift) ? @selector(redo:) : @selector(undo:);
                if (action && [NSApp sendAction:action to:responder from:nil]) return nil;
                return event;
            }] retain];
        *result = handle;
        return REAKTOR_WEB_OK;
    });
}
int reaktor_web_bounds(reaktor_web_handle handle, double x, double y, double width, double height, int visible) {
    return with_session(handle, [&](Session &s) {
        if (width < 0 || height < 0) throw std::runtime_error("Negative WebView size");
        auto bounds = s.parent.bounds;
        double native_y = s.parent.flipped ? y : bounds.size.height - y - height;
        s.widget.frame = NSMakeRect(x, native_y, width, height);
        s.widget.hidden = !visible || width == 0 || height == 0;
    });
}
int reaktor_web_load_url(reaktor_web_handle handle, const char *url) {
    if (!url) return REAKTOR_WEB_INVALID_ARGUMENT;
    return with_session(handle, [&](Session &s) { check(webview_navigate(s.browser, url)); });
}
int reaktor_web_load_html(reaktor_web_handle handle, const char *html) {
    if (!html) return REAKTOR_WEB_INVALID_ARGUMENT;
    return with_session(handle, [&](Session &s) { check(webview_set_html(s.browser, html)); });
}
int reaktor_web_execute(reaktor_web_handle handle, const char *script) {
    if (!script) return REAKTOR_WEB_INVALID_ARGUMENT;
    return with_session(handle, [&](Session &s) { check(webview_eval(s.browser, script)); });
}
int reaktor_web_evaluate(reaktor_web_handle handle, const char *request_id, const char *script) {
    if (!request_id || !script) return REAKTOR_WEB_INVALID_ARGUMENT;
    return with_session(handle, [&](Session &s) {
        NSString *request = ns(request_id);
        const auto session_handle = s.handle;
        [s.view evaluateJavaScript:ns(script) completionHandler:^(id value, NSError *error) {
            if (auto current = lookup(session_handle)) {
                id json = value ?: [NSNull null];
                if (![NSJSONSerialization isValidJSONObject:@[json]]) json = [NSNull null];
                emit(*current, @{@"kind":@"evaluation", @"id":request, @"value":json,
                    @"error":error.localizedDescription ?: @""});
            }
        }];
    });
}
int reaktor_web_control(reaktor_web_handle handle, int operation, double value) {
    return with_session(handle, [&](Session &s) {
        switch (operation) {
            case 0: [s.view reload]; break;
            case 1: [s.view goBack]; break;
            case 2: [s.view goForward]; break;
            case 3: if (value < 0.25 || value > 5.0) throw std::runtime_error("Invalid zoom"); s.view.pageZoom = value; break;
            default: throw std::runtime_error("Use Safari Develop to inspect WKWebView; public in-app DevTools opening is unavailable");
        }
    });
}
int reaktor_web_clear_assets(reaktor_web_handle handle) {
    return with_session(handle, [](Session &s) { s.assets.clear(); s.asset_bytes = 0; });
}
int reaktor_web_asset(reaktor_web_handle handle, const char *path, const char *mime, const uint8_t *bytes, uint32_t length, const char *csp) {
    if (!path || !mime || !csp || (!bytes && length) || length > 8 * 1024 * 1024) return REAKTOR_WEB_INVALID_ARGUMENT;
    return with_session(handle, [&](Session &s) {
        auto old = s.assets.find(path);
        size_t total = s.asset_bytes + length - (old == s.assets.end() ? 0 : old->second.bytes.size());
        if (total > 64 * 1024 * 1024) throw std::runtime_error("Bundle exceeds 64 MiB");
        s.assets[path] = Asset{std::vector<uint8_t>(bytes, bytes + length), mime, csp};
        s.asset_bytes = total;
    });
}
int reaktor_web_focus(reaktor_web_handle handle) {
    return with_session(handle, [](Session &s) { [s.parent.window makeFirstResponder:s.view]; });
}
int reaktor_web_destroy(reaktor_web_handle handle) {
    return on_main([&] { sessions.erase(handle); return REAKTOR_WEB_OK; });
}
const char *reaktor_web_last_error() { return last_error.c_str(); }
