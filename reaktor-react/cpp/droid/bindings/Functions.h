#pragma once

#include <droid/AndroidBase.h>

namespace Reaktor {
    struct NoArgNativeFunction: public jni::HybridClass<NoArgNativeFunction> {
        using FnType = std::function<jobject()>;
        static auto constexpr kJavaDescriptor = "Ldev/shibasis/flatinvoker/react/types/NoArgNativeFunction;";

        NoArgNativeFunction(FnType&& runnable) : runnable(std::move(runnable)) {}

        static void registerNatives() {
            registerHybrid({
                makeNativeMethod("nativeInvoke", NoArgNativeFunction::operator())
            });
        }

        jobject operator()() {
            return runnable();
        }

        friend HybridBase;

    private:
        FnType runnable;
    };

    struct SingleArgNativeFunction: public jni::HybridClass<SingleArgNativeFunction> {
        using FnType = std::function<jobject(jobject)>;
        static auto constexpr kJavaDescriptor = "Ldev/shibasis/flatinvoker/react/types/SingleArgNativeFunction;";

        SingleArgNativeFunction(FnType &&consumer) : consumer(std::move(consumer)) {}
        static void registerNatives() {
            registerHybrid({
                makeNativeMethod("nativeInvoke", SingleArgNativeFunction::operator())
            });
        }

        jobject operator()(jobject object) {
            return consumer(object);
        }

    private:
        FnType consumer;
    };

    // You can't create this from CPP
    // This struct will be used to add the functions on the Java side using AndroidInvoker
//    struct JavaPromise: jni::JavaClass<JavaPromise> {
//        static auto constexpr kJavaDescriptor = "Ldev/shibasis/flatinvoker/react/types/JavaPromise;";
//        static jni::local_ref<JavaPromise> create(
//                jni::local_ref<NoArgNativeFunction::JavaPart> resolve,
//                jni::local_ref<SingleArgNativeFunction::JavaPart> reject
//        ) {
//            return newInstance(
//                    jni::make_global(resolve),
//                    jni::make_global(reject)
//            );
//        }
//    };

}
