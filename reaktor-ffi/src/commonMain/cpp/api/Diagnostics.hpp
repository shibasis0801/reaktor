#pragma once
#include <reaktor/interop/Runtime.hpp>

namespace reaktor::interop {
inline void diagnostics(Runtime& runtime) {
    runtime.exportFunction("reaktor.diagnostics", "hello", [](std::string_view) {
        return std::string(R"json("Hello from C++ (Hermes 2)")json");
    });
}
}
