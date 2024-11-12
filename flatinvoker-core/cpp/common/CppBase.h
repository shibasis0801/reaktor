#pragma once
// todo Use Google C++ Style Guide
// https://google.github.io/styleguide/cppguide.html

#include <common/CBase.h>
#include <flatbuffers/flexbuffers.h>
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
#include <flatbuffers/flexbuffers.h>
#include <flatbuffers/idl.h>
#include <flatbuffers/buffer.h>

// basic
using std::pair;
using std::string;
using std::vector;
using std::unordered_map;
using std::make_shared;
using std::shared_ptr;
using std::unique_ptr;
using std::function;

// time
using std::chrono::high_resolution_clock;
using std::chrono::duration_cast;
using std::chrono::microseconds;
using std::chrono::milliseconds;

namespace FlatInvoker {
    struct Exception: std::exception {
        std::string message;
        Exception(std::string message);
        const char *what() const noexcept override;
    };
    // replace unordered_maps with FBVectors https://github.com/facebook/folly/blob/main/folly/docs/FBVector.md
// Understand cache locality
// Create Builder Pool and Reset on Finish
// todo critical please profile. I do not wish to use Java FlexBuffers, will increase maintainance, and anyway we need to pass to js
    namespace FlexStore {
        FlexPointer Create();
        // Also need to destroy when builder goes out of scope in parent language
        void Destroy(FlexPointer pointer);
        flexbuffers::Builder* Get(FlexPointer pointer);
        void Finish(FlexPointer pointer);
    };
}


#define GUARD(ptr) if ((ptr) == nullptr) return
#define GUARD_THROW(ptr, errorMessage) if ((ptr) == nullptr) throw Reaktor::ReaktorException(errorMessage)
#define GUARD_DEFAULT(ptr, fallback) if ((ptr) == nullptr) return fallback
//#define all(container) (container).begin(), (container).end()