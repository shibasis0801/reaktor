#pragma once

#include <jni.h>
#include <fbjni/fbjni.h>

namespace Reaktor {

    using namespace facebook;
    using namespace jni;

// TODO: Remove when fbjni 0.2.3 releases.
    template <typename K = jobject, typename V = jobject>
    struct JHashMap : JavaClass<JHashMap<K, V>, JMap<K, V>> {
    constexpr static auto kJavaDescriptor = "Ljava/util/HashMap;";

    static local_ref<JHashMap<K, V>> create();
};

}