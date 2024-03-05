#include <common/CppBase.hpp>

namespace Reaktor {
    ReaktorException::ReaktorException(std::string message): message(std::move(message)) {}
    const char *ReaktorException::what() const noexcept {
        return message.c_str();
    }

} // namespace Reaktor
