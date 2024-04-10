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
#include <jsi/jsi.h>
#include <ReactCommon/CallInvoker.h>

using std::string;
using std::vector;
using std::unordered_map;
using std::make_shared;
using std::shared_ptr;
using std::function;

// todo Write the ranges library with zip operator
// write a generic memo container which can memoize any function
#define repeat(i, n) for (int i = 0, _n = (n); i < _n; ++i)
#define countdown(i, n) for (int i = (n); i >= 0; --i)
#define GUARD(ptr) if ((ptr) == nullptr) return
#define GUARD_THROW(ptr, errorMessage) if ((ptr) == nullptr) throw ReaktorException(errorMessage)
#define GUARD_DEFAULT(ptr, fallback) if ((ptr) == nullptr) return fallback

inline int startTiming() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

inline int endTiming(int start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
    ).count() - start;
}


#ifndef __ANDROID__
#define __DARWIN__
#endif

typedef long long Long;

namespace jsi = facebook::jsi;
namespace react = facebook::react;

namespace Reaktor {
    struct Logger {
        std::string TAG;
        Logger(std::string TAG): TAG(TAG) {};
        virtual inline void Verbose(const std::string &message) {};
        virtual inline void Error(const std::string &errorMessage) {};
    };

    struct ReaktorException: std::exception {
        std::string message;
        ReaktorException(std::string message);
        const char *what() const noexcept override;
    };


    class Link {
        // Not shared_ptr because we don't want a leak. If it becomes null elsewhere, this file becomes no-op
        // Maybe weak_ptr
        jsi::Runtime *runtime;

    public:
        jsi::Runtime& getRuntime() const;

        std::shared_ptr<react::CallInvoker> jsCallInvoker;
        std::shared_ptr<react::CallInvoker> nativeCallInvoker;

        explicit Link(): runtime(nullptr), jsCallInvoker(nullptr), nativeCallInvoker(nullptr) {}
        explicit Link(jsi::Runtime *runtime): runtime(runtime), jsCallInvoker(nullptr), nativeCallInvoker(nullptr) {}

        ~Link();
        void install(
                jsi::Runtime *_runtime,
                std::shared_ptr<react::CallInvoker> _jsCallInvoker,
                std::shared_ptr<react::CallInvoker> _nativeCallInvoker
        );

        jsi::PropNameID createPropName(const std::string &propName) const;

        jsi::Function createFunction(const jsi::PropNameID &name, int argCount, jsi::HostFunctionType &&fn) const;
        jsi::Function createFunction(const std::string &name, int argCount, jsi::HostFunctionType &&fn) const;

        jsi::Value createFromUTF8String(const std::string &contents) const;
        jsi::Value createFromAsciiString(const std::string &contents) const;

        void createGlobalProperty(const std::string &name, jsi::Object object) const;
        jsi::Object createFromHostObject(std::shared_ptr<jsi::HostObject> hostObject) const;

        jsi::Value getProperty(const std::string &name) const;
        jsi::Function getFunction(const std::string &name) const;
        std::string getString(const jsi::Value &value) const;
    };

    Link& getLink();
    jsi::Runtime& getRuntime();

    // Wrap a raw JSI reference
    struct FunctionHolder {
        void invoke() {}
        void construct() {}
    };

}

namespace jsi = facebook::jsi;
