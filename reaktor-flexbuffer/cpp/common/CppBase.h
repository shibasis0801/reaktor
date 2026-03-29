#pragma once

#include <common/CBase.h>
#include <flatbuffers/buffer.h>
#include <flatbuffers/flexbuffers.h>
#include <flatbuffers/idl.h>
#include <chrono>
#include <exception>
#include <functional>
#include <memory>
#include <queue>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <variant>
#include <vector>

using std::function;
using std::make_shared;
using std::pair;
using std::shared_ptr;
using std::string;
using std::string_view;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

using std::chrono::duration_cast;
using std::chrono::high_resolution_clock;
using std::chrono::microseconds;
using std::chrono::milliseconds;

namespace FlatInvoker {
    struct Exception : std::exception {
        explicit Exception(std::string message);
        const char* what() const noexcept override;

    private:
        std::string message_;
    };
}

#define GUARD(ptr) if ((ptr) == nullptr) return
#define GUARD_DEFAULT(ptr, fallback) if ((ptr) == nullptr) return fallback
#define GUARD_THROW(ptr, errorMessage) if ((ptr) == nullptr) throw FlatInvoker::Exception(errorMessage)
