#pragma once

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

#define repeat(i, n) for (size_t i = 0; (i) < (n); ++(i))
#define GUARD(ptr) if ((ptr) == nullptr) return
#define GUARD_THROW(ptr, errorMessage) if ((ptr) == nullptr) throw ReaktorException(errorMessage)
#define GUARD_DEFAULT(ptr, fallback) if ((ptr) == nullptr) return fallback
//#define in(container, element) (container).find(element) != (container).end()
//#define all(container) (container).begin(), (container).end()


inline int getNumber() { return 42; }