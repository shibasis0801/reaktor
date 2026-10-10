#include <reaktor/interop/Runtime.hpp>
#include <kernel/RuntimeKernel.hpp>
#ifdef REAKTOR_INTEROP_EXPORTS_HEADER
#include REAKTOR_INTEROP_EXPORTS_HEADER
#endif

namespace reaktor::interop {
Runtime::Runtime(Dispatch host): kernel_(std::make_unique<kernel::RuntimeKernel>(std::move(host))) {
#ifdef REAKTOR_INTEROP_EXPORTS_HEADER
    generated::install(*this);
#endif
}
Runtime::~Runtime() = default;
void Runtime::exportFunction(std::string module, std::string operation, Function function) {
    kernel_->exportFunction(std::move(module), std::move(operation), std::move(function));
}
std::string Runtime::callNative(std::string_view module, std::string_view operation, std::string_view request) {
    return kernel_->callNative(module, operation, request);
}
std::string Runtime::callTypeScript(std::string_view module, std::string_view operation, std::string_view request) {
    return kernel_->callTypeScript(module, operation, request);
}
void Runtime::evaluate(std::string_view source, std::string_view sourceName) { kernel_->evaluate(source, sourceName); }
void Runtime::checkCanClose() const { kernel_->checkCanClose(); }
}
