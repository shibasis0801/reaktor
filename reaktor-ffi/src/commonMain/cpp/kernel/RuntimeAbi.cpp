#include <reaktor/interop/Runtime.h>
#include <reaktor/interop/Runtime.hpp>
#include <stdexcept>

struct ReaktorInteropResult { std::string value; std::string error; bool failed = false; };
struct ReaktorInteropRuntime { reaktor::interop::Runtime value; explicit ReaktorInteropRuntime(reaktor::interop::Dispatch host): value(std::move(host)) {} };
using Result = std::unique_ptr<ReaktorInteropResult, decltype(&ReaktorInterop_release)>;

ReaktorInteropResult* ReaktorInterop_success(const char* value) { return new ReaktorInteropResult{value ? value : "", "", false}; }
ReaktorInteropResult* ReaktorInterop_failure(const char* error) { return new ReaktorInteropResult{"", error ? error : "Unknown interop failure", true}; }
const char* ReaktorInterop_value(const ReaktorInteropResult* result) { return result && !result->failed ? result->value.c_str() : nullptr; }
const char* ReaktorInterop_error(const ReaktorInteropResult* result) { return result && result->failed ? result->error.c_str() : nullptr; }
void ReaktorInterop_release(ReaktorInteropResult* result) { delete result; }

template<class Operation> static ReaktorInteropResult* attempt(Operation operation) {
    try { return ReaktorInterop_success(operation().c_str()); }
    catch (const std::exception& error) { return ReaktorInterop_failure(error.what()); }
    catch (...) { return ReaktorInterop_failure("Unknown native interop exception"); }
}

static reaktor::interop::Runtime& live(ReaktorInteropRuntime* runtime) {
    if (!runtime) throw std::invalid_argument("Native runtime is null");
    return runtime->value;
}
static const char* required(const char* value) {
    if (!value) throw std::invalid_argument("Native argument is null");
    return value;
}

static reaktor::interop::Dispatch host(ReaktorHostDispatch dispatch, void* context) {
    if (!dispatch) return {};
    return [=](std::string_view module, std::string_view operation, std::string_view request) {
        Result result(dispatch(context, std::string(module).c_str(), std::string(operation).c_str(), std::string(request).c_str()), ReaktorInterop_release);
        if (!result) throw std::runtime_error("Host dispatch returned no result");
        if (const auto error = ReaktorInterop_error(result.get())) throw std::runtime_error(error);
        return std::string(ReaktorInterop_value(result.get()));
    };
}

ReaktorInteropRuntime* ReaktorInterop_create(ReaktorHostDispatch dispatch, void* context) {
    try { return new ReaktorInteropRuntime(host(dispatch, context)); } catch (...) { return nullptr; }
}
ReaktorInteropResult* ReaktorInterop_close(ReaktorInteropRuntime* runtime) {
    return attempt([=] { if (runtime) { runtime->value.checkCanClose(); delete runtime; } return std::string(); });
}
ReaktorInteropResult* ReaktorInterop_native(ReaktorInteropRuntime* runtime, const char* module, const char* operation, const char* request) {
    return attempt([=] { return live(runtime).callNative(required(module), required(operation), required(request)); });
}
ReaktorInteropResult* ReaktorInterop_typescript(ReaktorInteropRuntime* runtime, const char* module, const char* operation, const char* request) {
    return attempt([=] { return live(runtime).callTypeScript(required(module), required(operation), required(request)); });
}
ReaktorInteropResult* ReaktorInterop_evaluate(ReaktorInteropRuntime* runtime, const char* source, const char* sourceName) {
    return attempt([=] { live(runtime).evaluate(required(source), required(sourceName)); return std::string(); });
}
ReaktorInteropResult* ReaktorInterop_export(ReaktorInteropRuntime* runtime, const char* module, const char* operation, ReaktorHostDispatch dispatch, void* context) {
    return attempt([=] {
        auto function = host(dispatch, context);
        if (!function) throw std::invalid_argument("Native export requires a callback");
        const std::string moduleName(required(module)), operationName(required(operation));
        live(runtime).exportFunction(moduleName, operationName, [=](std::string_view request) { return function(moduleName, operationName, request); });
        return std::string();
    });
}
