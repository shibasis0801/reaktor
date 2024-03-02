#pragma once
// todo Use Google C++ Style Guide
// https://google.github.io/styleguide/cppguide.html
#include <string>
#include <vector>
#include <string>
#include <queue>
#include <utility>
#include <unordered_map>
#include <stdexcept>
#include <thread>
#include <atomic>
#include <chrono>
#include <variant>

using std::string;
using std::vector;
using std::unordered_map;
using std::make_shared;
using std::shared_ptr;
using std::function;

namespace Reaktor {
    struct ReaktorException: std::exception {
        std::string message;
        ReaktorException(std::string message);
        const char *what() const noexcept override;
    };
}


#define repeat(i, n) for (size_t i = 0; (i) < (n); ++(i))
#define GUARD(ptr) if ((ptr) == nullptr) return
#define GUARD_THROW(ptr, errorMessage) if ((ptr) == nullptr) throw Reaktor::ReaktorException(errorMessage)
#define GUARD_DEFAULT(ptr, fallback) if ((ptr) == nullptr) return fallback
//#define all(container) (container).begin(), (container).end()
