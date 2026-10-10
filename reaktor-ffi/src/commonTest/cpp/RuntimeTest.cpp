#include <reaktor/interop/Runtime.h>
#include <reaktor/interop/Runtime.hpp>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <thread>

static void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
template<class Operation> static void rejects(Operation operation, const char* expected) {
    try { operation(); } catch (const std::exception& error) {
        require(std::string(error.what()).find(expected) != std::string::npos, error.what());
        return;
    }
    throw std::runtime_error("Expected operation to fail");
}
static ReaktorInteropResult* callback(void* context, const char* module, const char* operation, const char* request) {
    ++*static_cast<unsigned*>(context);
    require(std::string(module) == "host" && std::string(operation) == "echo", "Host dispatch lost operation identity");
    return ReaktorInterop_success(request);
}
int main(int argc, char** argv) {
    try {
        require(argc == 2, "Expected the generated TypeScript bundle path");
        std::ifstream input(argv[1]); require(input.good(), "TypeScript bundle is missing");
        std::ostringstream source; source << input.rdbuf();
        unsigned callbacks = 0;
        reaktor::interop::Runtime runtime([&](std::string_view module, std::string_view operation, std::string_view request) {
            require(module == "host" && operation == "echo", "Unexpected host operation"); ++callbacks; return std::string(request);
        });
        runtime.exportFunction("cpp", "echo", [](std::string_view request) { return std::string(request); });
        runtime.evaluate(source.str(), "reaktor-ffi.js");
        require(runtime.callTypeScript("reaktor.diagnostics", "hello", "{}") == R"json("Hello from C++ (Hermes 2)")json",
                "Declarative C++ exports are missing from the runtime");
        runtime.evaluate(R"(ReaktorFfi.exportModule('roundtrip', {
            cpp: request => ReaktorFfi.native.module('cpp').function('echo')(request),
            host: request => ReaktorFfi.native.module('host').function('echo')(request)
        });)", "roundtrip.js");
        const std::string request = R"({"text":"unicode λ 🎉","nested":{"count":42},"nullable":null})";
        require(runtime.callTypeScript("roundtrip", "cpp", request) == request, "C++ → TypeScript → C++ round trip failed");
        require(runtime.callTypeScript("roundtrip", "host", request) == request && callbacks == 1, "TypeScript host callback failed");
        require(runtime.callNative("host", "echo", request) == request && callbacks == 2, "C++ host callback failed");
        rejects([&] { runtime.callTypeScript("roundtrip", "missing", request); }, "No TypeScript operation");
        rejects([&] { runtime.exportFunction("cpp", "echo", [](std::string_view value) { return std::string(value); }); }, "already exported");
        std::thread different([&] { rejects([&] { runtime.callNative("cpp", "echo", request); }, "different thread"); }); different.join();
        auto* abi = ReaktorInterop_create(callback, &callbacks);
        require(abi, "C ABI runtime creation failed");
        auto* result = ReaktorInterop_native(abi, "host", "echo", request.c_str());
        require(!ReaktorInterop_error(result) && std::string(ReaktorInterop_value(result)) == request, "C ABI callback round trip failed");
        ReaktorInterop_release(result);
        result = ReaktorInterop_native(nullptr, "host", "echo", request.c_str());
        require(ReaktorInterop_error(result), "Null runtime must return an owned failure"); ReaktorInterop_release(result);
        result = ReaktorInterop_native(abi, nullptr, "echo", request.c_str());
        require(ReaktorInterop_error(result), "Null module must return an owned failure"); ReaktorInterop_release(result);
        const reaktor::interop::Codec<std::string> json{[](const auto& value) { return value; }, [](std::string_view value) { return std::string(value); }};
        require(runtime.callTypeScript("roundtrip", "cpp", request, json, json) == request, "Typed C++ import failed");
        auto* closed = ReaktorInterop_close(abi); require(!ReaktorInterop_error(closed), "C ABI close failed"); ReaktorInterop_release(closed);
        std::cout << "C++, TypeScript, host callbacks, C ABI ownership and thread checks passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
