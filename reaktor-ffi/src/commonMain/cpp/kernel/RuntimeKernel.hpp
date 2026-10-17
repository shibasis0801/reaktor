#pragma once

#include <reaktor/interop/Runtime.hpp>
#include <hermes/hermes.h>
#include <map>
#include <thread>

namespace reaktor::interop::kernel {
class RuntimeKernel final {
public:
    explicit RuntimeKernel(Dispatch host): host_(std::move(host)), owner_(std::this_thread::get_id()) {}
    void exportFunction(std::string module, std::string operation, Function function);
    std::string callNative(std::string_view module, std::string_view operation, std::string_view request);
    std::string callTypeScript(std::string_view module, std::string_view operation, std::string_view request);
    void evaluate(std::string_view source, std::string_view sourceName);
    void checkCanClose() const;
private:
    Dispatch host_;
    std::thread::id owner_;
    unsigned activeCalls_ = 0;
    std::map<std::pair<std::string, std::string>, Function> functions_;
    std::unique_ptr<facebook::hermes::HermesRuntime> javascript_;
    facebook::hermes::HermesRuntime& javascript();
    void checkThread() const;
    class Call;
};
}
