#include <droid/Invoker.h>

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
        static unordered_map<long, jni::global_ref<jobject>> javaObjects;
        static std::mutex javaObjectsLock;
    private:
        ~KeepAlive() = default;
        KeepAlive() = default;

    };

    long KeepAlive::javaObjectsIndex;
    unordered_map<long, jni::global_ref<jobject>> KeepAlive::javaObjects;
    std::mutex KeepAlive::javaObjectsLock;


    inline void convertArgs(
            const jsi::Value *from,
            jvalue *to,
            const int count
    ) {
        repeat(i, count) {
            auto [ value, type ] = toJNI(from[i]);
            to[i] = value;
        }
    }

    jobject AndroidInvoker::invokeMethod(
            const std::string &methodName,
            jvalue *args,
            FunctionDescriptor &descriptor
    ) {
        return nullptr;
    }

    /**
     * Only Android Issue
     *  Unfortunately, I don't understand type_traits well enough to not use raw JNI.
     *  JNI calls have a non trivial overhead.
     *  So for an object with lots of fields / recursive objects.
     *  We will be making tons of JNI calls.
     *  This is suboptimal.
     *
     *  Ideally we should do binary serialization (protobuf or (capnproto<-faster))
     *  For large objects / arrays on Android, and pass the entire object as Bytes here.
     *
     *  Then in C++ we create the JSI Object
     *  https://github.com/evolvedbinary/jni-benchmarks#jni-object-creation-benchmarks
     *  Benchmarks + suprising source of reference for JNI calls.
     */
    jsi::Value AndroidInvoker::operator()(
            const char *name,
            const jsi::Value *args,
            const FunctionDescriptor &descriptor
    ) {
        try {
            auto [jvmSignature, _, argCount, returnType] = descriptor;
            auto env = jni::Environment::current();
            jni::JniLocalScope scope(env, 20);

            GUARD_THROW(instance, "Null Instance");
            const auto ref = instance.get();

            Log.Verbose(jvmSignature);

            auto clazz = env->GetObjectClass(ref);
            auto method = env->GetMethodID(clazz, name, jvmSignature.c_str());

            throwIfJNIError("GetMethodID");

            jvalue jniArgs[argCount];
            convertArgs(args, jniArgs, argCount);

            switch (returnType) {
                case String:
                    return invokeStringMethod(method, jniArgs);
                case Number:
                    return invokeDoubleMethod(method, jniArgs);
                case Object:
                    return invokeHashMapMethod(method, jniArgs);
                case Array:
                    return invokeArrayMethod(method, jniArgs);
                case PromiseType:
                    return invokePromiseMethod(method, jniArgs);
                case FlowType:
                    return invokeFlowMethod(method, jniArgs);
                default:
                    return {};
            }
        }
        catch (std::exception e) {
            Log.Error(e.what());
            return {};
        }
    }



    jsi::Value AndroidInvoker::invokeStringMethod(
            jmethodID method,
            jvalue jniArgs[]
    ) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();

        auto result = env->CallObjectMethodA(ref, method, jniArgs);

        throwIfJNIError("invokeStringMethod");
        GUARD_THROW(result, "invokeStringMethod");

        return fromJNI(result);
    }

    jsi::Value AndroidInvoker::invokeDoubleMethod(jmethodID method, jvalue *jniArgs) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();

        auto result = env->CallDoubleMethodA(ref, method, jniArgs);

        jvalue value = { .d = result };

        throwIfJNIError("invokeDoubleMethod");
        return fromJNI(value);
    }

    jsi::Value AndroidInvoker::invokeHashMapMethod(jmethodID method, jvalue *jniArgs) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();

        auto result = env->CallObjectMethodA(ref, method, jniArgs);


        throwIfJNIError("invokeHashMapMethod");
        return fromJNI(result);
    }

    jsi::Value AndroidInvoker::invokeArrayMethod(jmethodID method, jvalue *jniArgs) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();
        auto result = env->CallObjectMethodA(ref, method, jniArgs);

        throwIfJNIError("invokeArrayMethod");
        return fromJNI(result);
    }

    void AndroidInvoker::invokeVoidMethod(
            const std::string& name,
            const std::string& signature,
            jvalue *jniArgs
    ) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();
        auto clazz = env->GetObjectClass(ref);
        auto method = env->GetMethodID(clazz, name.c_str(), signature.c_str());

        env->CallVoidMethodA(ref, method, jniArgs);

        throwIfJNIError("invokeString: CallObjectMethodA");
    }

    jsi::Value AndroidInvoker::invokePromiseMethod(
            jmethodID method,
            jvalue *jniArgs
    ) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();

        auto javaPromise = env->CallObjectMethodA(ref, method, jniArgs);
        throwIfJNIError("invokePromiseMethod");

        auto promise = make_shared<Promise>();
        Async::add(promise);
        auto jsPromise = promise->create();


        auto resolver = SingleArgNativeFunction::newObjectCxxArgs([=](jobject data) {
            auto id = KeepAlive::StoreJavaGlobal(data);
            getLink().jsCallInvoker->invokeAsync([promise, id] {
                auto ref = KeepAlive::GetJavaGlobal(id);
                auto value = make_shared<jsi::Value>(fromJNI(ref));
                promise->notify(value);
                KeepAlive::ClearJavaGlobal(id);
                // Need to call KeepAlive::ClearJavaGlobal to prevent leak
                // How to clear resolver without a ref to it here ?
                // Use strings ?
            });
            return nullptr;
        });

        auto signature = Reaktor::getJavaSignature(DataType::Undefined, {
                {"", DataType::SingleArgFn}
        });


        // Should use invoker. Refactor
        auto clazz = env->GetObjectClass(javaPromise);
        auto setResolver = env->GetMethodID(clazz, "setResolver", signature.c_str());
        auto resolverId = KeepAlive::StoreJavaGlobal(resolver.get());
        env->CallVoidMethod(javaPromise, setResolver, KeepAlive::GetJavaGlobal(resolverId).get());
        throwIfJNIError("invokePromiseMethod: setResolver");

        return jsPromise;
    }

    jsi::Value AndroidInvoker::invokeFlowMethod(
            jmethodID method,
            jvalue *jniArgs
    ) {
        auto env = jni::Environment::current();
        const auto ref = instance.get();

        auto javaPromise = env->CallObjectMethodA(ref, method, jniArgs);
        throwIfJNIError("invokeFlowMethod");

        auto flow = make_shared<Flow>();
        Async::add(flow);
        auto jsFlow = flow->create();

        auto resolver = SingleArgNativeFunction::newObjectCxxArgs([=](jobject data) {
            auto id = KeepAlive::StoreJavaGlobal(data);

            getLink().jsCallInvoker->invokeAsync([flow, id] {
                auto ref = KeepAlive::GetJavaGlobal(id);
                auto value = make_shared<jsi::Value>(fromJNI(ref));
                flow->notify(value);
                KeepAlive::ClearJavaGlobal(id);
            });
            return nullptr;
        });
        auto resolverId = KeepAlive::StoreJavaGlobal(resolver.get());

        auto signature = Reaktor::getJavaSignature(DataType::Undefined, {
                {"", DataType::SingleArgFn}
        });

        // Should use invoker. Refactor
        auto clazz = env->GetObjectClass(javaPromise);
        auto setResolver = env->GetMethodID(clazz, "setResolver", signature.c_str());
        env->CallVoidMethod(javaPromise, setResolver, KeepAlive::GetJavaGlobal(resolverId).get()); // Need to call KeepAlive::ClearJavaGlobal to prevent leak
        throwIfJNIError("invokeFlowMethod: setResolver");

        return jsFlow;
    }
}
