#pragma once
#include <Base.h>
#include <types/Types.h>

namespace Reaktor {
    struct FunctionDescriptor {
        std::string jvmSignature = ""; // is empty string on iOS
        std::string iosSelector = ""; // is empty string on android
        int argumentCount;
        DataType returnType;

        struct Input {
            DataType returnType;
            FunctionParamList parameterList;
        };
    };


    std::pair<std::string, FunctionDescriptor> descriptor(
            const std::string &name,
            const FunctionDescriptor::Input &input
    );


    /** We may need to make this thread safe (RAII locking)
      * Can't inherit CallInvoker as we need to pass arguments
      * This structure allows you full freedom in how to implement,
      * and has a single point of entry.
      */
    struct PlatformInvoker {
        virtual jsi::Value operator()(
                const char *name,
                const jsi::Value *args,
                const FunctionDescriptor &descriptor
        ) = 0;

        // You may need to nullify any references to native objects
        virtual ~PlatformInvoker() = default;
    };

    /*
     * A Reaktor Host Object is the C++ controller object
     * Extends HostObject so that this is exposed to JSI
     * It is attached to the global object during installation
     *
     * Extends PlatformInvoker to allow for pure C++ calls
     * Allowing you to directly call things like tensorflow/compression/etc
     *
     * Pure C++ also allows you to add WebAssembly support later
     * Just we will need to create a type like jsi::Value (ReaktorValue)
     * And then use that here instead.
     *
     * It holds a PlatformInvoker which allows you to decouple your Android/iOS code
     * from how this module's API
     *
     * This is platform agnostic and to support a new platform (ex: Windows/Mac),
     * we just need to implement the PlatformInvoker and all other parts will just work.
     *
     * PlatformInvoker as the name suggests handles all platform invokation/conversion
     *
     */
    class ReaktorHostObject: public jsi::HostObject, public PlatformInvoker {
        std::shared_ptr<PlatformInvoker> platformInvoker;

    public:
        Logger Log;
        unordered_map<std::string, FunctionDescriptor> platformFunctions;
        unordered_map<std::string, FunctionDescriptor> nativeFunctions;

        explicit ReaktorHostObject(
                std::shared_ptr<PlatformInvoker> platformInvoker,
                Logger Log
        ): platformInvoker(std::move(platformInvoker)), Log(Log) {}
        ~ReaktorHostObject() override { platformInvoker.reset(); }

        jsi::Value operator()(const char *name, const jsi::Value *args,
                              const FunctionDescriptor &descriptor) override {
            throw ReaktorException("Native Invoker not implemented but you have specified native functions");
        }

        inline jsi::Value invokePlatform(
                const char *name,
                const jsi::Value *args,
                const FunctionDescriptor &descriptor
        ) {
            GUARD_THROW(platformInvoker, "Platform Invoker not supplied");
            return platformInvoker->operator()(name, args, descriptor);
        }

        // FB also constructs Functions on every call, will try caching if that does not give issues
        jsi::Value get(jsi::Runtime &runtime, const jsi::PropNameID &name) override {
            auto fnName = name.utf8(getLink().getRuntime());

            try {
                if (platformFunctions.contains(fnName)) {
                    auto fnDescriptor = platformFunctions[fnName];
                    return getLink().createFunction(
                            name,
                            fnDescriptor.argumentCount,
                            [this, fnName, fnDescriptor] (jsi::Runtime& runtime, const jsi::Value &self, const jsi::Value *args, size_t count) -> jsi::Value {
                                return invokePlatform(fnName.c_str(), args, fnDescriptor);
                            }
                    );
                }

                if (nativeFunctions.contains(fnName)) {
                    auto fnDescriptor = nativeFunctions[fnName];
                    return getLink().createFunction(
                            name,
                            fnDescriptor.argumentCount,
                            [this, fnName, fnDescriptor] (jsi::Runtime& runtime, const jsi::Value &self, const jsi::Value *args, size_t count) -> jsi::Value {
                                return this->operator()(fnName.c_str(), args, fnDescriptor);
                            }
                    );
                }
            } catch (std::exception e) {
                Log.Error(e.what());
            }

            return jsi::Value();
        }

        vector<jsi::PropNameID> getPropertyNames(jsi::Runtime &rt) override {
            vector<jsi::PropNameID> properties;

            for (auto [key, value]: platformFunctions) {
                properties.push_back(getLink().createPropName(key));
            }

            for (auto [key, value]: nativeFunctions) {
                properties.push_back(getLink().createPropName(key));
            }

            return properties;
        }

        void set(jsi::Runtime &runtime, const jsi::PropNameID &name, const jsi::Value &value) override {
            throw ReaktorException("Modifying HostObjects from JS is not supported yet.");
        }
    };
}
