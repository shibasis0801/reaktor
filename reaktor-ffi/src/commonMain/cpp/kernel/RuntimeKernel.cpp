#include "RuntimeKernel.hpp"
#include <stdexcept>

namespace jsi = facebook::jsi;
namespace reaktor::interop::kernel {
class RuntimeKernel::Call {
    RuntimeKernel& kernel_;
public:
    explicit Call(RuntimeKernel& kernel): kernel_(kernel) { kernel_.checkThread(); ++kernel_.activeCalls_; }
    ~Call() { --kernel_.activeCalls_; }
};

void RuntimeKernel::checkThread() const {
    if (std::this_thread::get_id() != owner_) throw std::logic_error("Interop runtime used from a different thread");
}

void RuntimeKernel::checkCanClose() const {
    checkThread();
    if (activeCalls_) throw std::logic_error("Cannot close an interop runtime inside an active call");
}

void RuntimeKernel::exportFunction(std::string module, std::string operation, Function function) {
    checkThread();
    if (module.empty() || operation.empty() || module.find('\0') != std::string::npos || operation.find('\0') != std::string::npos || !function) throw std::invalid_argument("A native export needs a module, operation and implementation");
    if (!functions_.emplace(std::make_pair(std::move(module), std::move(operation)), std::move(function)).second)
        throw std::invalid_argument("Native operation is already exported");
}

std::string RuntimeKernel::callNative(std::string_view module, std::string_view operation, std::string_view request) {
    Call call(*this);
    const auto function = functions_.find({std::string(module), std::string(operation)});
    if (function != functions_.end()) return function->second(request);
    if (host_) return host_(module, operation, request);
    throw std::invalid_argument("No native or Kotlin operation is exported: " + std::string(module) + "." + std::string(operation));
}

facebook::hermes::HermesRuntime& RuntimeKernel::javascript() {
    checkThread();
    if (javascript_) return *javascript_;
    javascript_ = facebook::hermes::makeHermesRuntime(hermes::vm::RuntimeConfig::Builder()
        .withIntl(false).withES6Class(true).build());
    auto& runtime = *javascript_;
    jsi::Object bridge(runtime);
    bridge.setProperty(runtime, "invoke", jsi::Function::createFromHostFunction(runtime,
        jsi::PropNameID::forAscii(runtime, "invoke"), 3,
        [this](jsi::Runtime& runtime, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count != 3 || !args[0].isString() || !args[1].isString() || !args[2].isString())
                throw jsi::JSError(runtime, "Native invoke expects module, operation and JSON strings");
            try {
                return jsi::String::createFromUtf8(runtime, callNative(args[0].asString(runtime).utf8(runtime),
                    args[1].asString(runtime).utf8(runtime), args[2].asString(runtime).utf8(runtime)));
            } catch (const std::exception& error) { throw jsi::JSError(runtime, error.what()); }
        }));
    runtime.global().setProperty(runtime, "ReaktorNative", std::move(bridge));
    return runtime;
}

void RuntimeKernel::evaluate(std::string_view source, std::string_view sourceName) {
    Call call(*this);
    javascript().evaluateJavaScript(std::make_unique<jsi::StringBuffer>(std::string(source)), std::string(sourceName));
}

std::string RuntimeKernel::callTypeScript(std::string_view module, std::string_view operation, std::string_view request) {
    Call call(*this);
    auto& runtime = javascript();
    const auto bridge = runtime.global().getProperty(runtime, "ReaktorTypeScript");
    if (!bridge.isObject()) throw std::logic_error("Load a TypeScript interop bundle before importing its modules");
    const auto object = bridge.asObject(runtime);
    const auto invoke = object.getPropertyAsFunction(runtime, "invoke");
    const auto result = invoke.callWithThis(runtime, object, jsi::String::createFromUtf8(runtime, std::string(module)),
        jsi::String::createFromUtf8(runtime, std::string(operation)), jsi::String::createFromUtf8(runtime, std::string(request)));
    if (!result.isString()) throw std::logic_error("A TypeScript interop operation must return JSON synchronously");
    return result.asString(runtime).utf8(runtime);
}
}
