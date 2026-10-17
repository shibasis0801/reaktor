#include <common/CppBase.h>

namespace FlatInvoker {
    Exception::Exception(std::string message) : message_(std::move(message)) {}

    const char* Exception::what() const noexcept {
        return message_.c_str();
    }
}
