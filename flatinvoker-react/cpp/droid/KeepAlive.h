#pragma once


namespace Reaktor {
    struct KeepAlive {
        static long StoreJavaGlobal(const jobject &obj) {
            std::lock_guard guard(javaObjectsLock);
            auto idx = javaObjectsIndex;
            javaObjects[idx] = jni::make_global(obj);
            javaObjectsIndex += 1;
            return idx;
        }

        static jni::global_ref<jobject> GetJavaGlobal(long id) {
            if (javaObjects.contains(id)) {
                return javaObjects[id];
            }
            throw std::out_of_range("Get: ID not found for requested JavaGlobal");
        }

        static void ClearJavaGlobal(long id) {
            std::lock_guard guard(javaObjectsLock);
            auto match = javaObjects.find(id);
            if (match != javaObjects.end()) {
                match->second.reset();
                javaObjects.erase(match);
                return;
            }
            throw std::out_of_range("Delete: ID not found for requested JavaGlobal");
        };


        static long javaObjectsIndex;
        // even
        static unordered_map<long, jni::global_ref<jobject>> javaObjects;
        static std::mutex javaObjectsLock;
    private:
        ~KeepAlive() = default;
        KeepAlive() = default;

    };

}