#pragma once

#include <functional>
#include <memory>
#include <string>
#include <string_view>

namespace reaktor::interop {
namespace kernel { class RuntimeKernel; }
using Dispatch = std::function<std::string(std::string_view, std::string_view, std::string_view)>;
using Function = std::function<std::string(std::string_view)>;

template<class T> struct Codec {
    std::function<std::string(const T&)> encode;
    std::function<T(std::string_view)> decode;
};

/** The C++ API owns a kernel; JSON codecs remain selectable without another parser dependency. */
class Runtime final {
public:
    explicit Runtime(Dispatch host = {});
    ~Runtime();
    Runtime(const Runtime&) = delete;
    Runtime& operator=(const Runtime&) = delete;
    void exportFunction(std::string module, std::string operation, Function function);

    template<class Request, class Response>
    Response callNative(std::string_view module, std::string_view operation, const Request& input,
                        const Codec<Request>& request, const Codec<Response>& response) {
        return response.decode(callNative(module, operation, request.encode(input)));
    }
    template<class Request, class Response>
    Response callTypeScript(std::string_view module, std::string_view operation, const Request& input,
                            const Codec<Request>& request, const Codec<Response>& response) {
        return response.decode(callTypeScript(module, operation, request.encode(input)));
    }
    std::string callNative(std::string_view module, std::string_view operation, std::string_view request);
    std::string callTypeScript(std::string_view module, std::string_view operation, std::string_view request);
    void evaluate(std::string_view source, std::string_view sourceName);
    void checkCanClose() const;

    template<class Request, class Response>
    void exportFunction(std::string module, std::string operation, Codec<Request> request, Codec<Response> response,
                        std::function<Response(Request)> implementation) {
        exportFunction(std::move(module), std::move(operation), [=](std::string_view input) {
            return response.encode(implementation(request.decode(input)));
        });
    }
private:
    std::unique_ptr<kernel::RuntimeKernel> kernel_;
};
}
