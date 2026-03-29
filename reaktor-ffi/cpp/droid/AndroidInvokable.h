#pragma once

#include <array>
#include <fbjni/fbjni.h>

namespace jni = facebook::jni;

// C++ Magic, this code runs on the compiler and code is modified
namespace reaktor::ffi {
template <size_t N>
struct JavaDescriptorString {
    std::array<char, N + 2> value{};

    consteval explicit JavaDescriptorString(const char (&name)[N]) {
        const bool wrapped = N >= 3 && name[0] == 'L' && name[N - 2] == ';';
        size_t out = 0;
        if (!wrapped) {
            value[out++] = 'L';
        }
        for (size_t i = 0; i < N - 1; ++i) {
            const char current = name[i];
            if (wrapped && (i == 0 || i == N - 2)) {
                value[out++] = current;
            } else {
                value[out++] = current == '.' ? '/' : current;
            }
        }
        if (!wrapped) {
            value[out++] = ';';
        }
        value[out] = '\0';
    }

    constexpr const char *data() const {
        return value.data();
    }

    constexpr operator const char *() const {
        return data();
    }
};

template <size_t N>
consteval auto javaDescriptor(const char (&name)[N]) {
    return JavaDescriptorString<N>(name);
}
} // namespace reaktor::ffi

#define JAVA_DESCRIPTOR(name) ::reaktor::ffi::javaDescriptor(name)

struct KotlinInvokable: jni::JavaClass<KotlinInvokable> {
    static constexpr auto kJavaDescriptor = JAVA_DESCRIPTOR("dev.shibasis.reaktor.ffi.Invokable");
    void invokeSync(jbyteArray array) {
        static const auto method = getClass()->getMethod<jlong(jbyteArray)>("invokeSync");
        method(self(), array);
    }
};

//class AndroidInvokable: Invokable {
//    // reset after object is no longer needed
//    jni::global_ref<KotlinInvokable> instance;
//public:
//    explicit AndroidInvokable(jni::alias_ref<KotlinInvokable> instance): instance(jni::make_global(instance)) {}
//    long invokeSync(const flexbuffers::Vector &payload) override;
//};
