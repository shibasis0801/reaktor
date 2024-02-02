#pragma once

#include <Base.h>
#include <ReaktorHostObject.h>
#include <types/Async.h>
#include <flatbuffers/flexbuffers.h>

namespace Reaktor {
    struct NetworkModule : public ReaktorHostObject {

        // Didn't work because builder was stack allocated and gets removed post call resulting in garbage
        const std::vector<uint8_t>& createFlexBuffer() {
            flexbuffers::Builder builder(1024);

            builder.Map([&] {
                builder.Int("intField", 42);
                builder.UInt("uintField", 123u);
                builder.Float("floatField", 3.14f);
                builder.Bool("boolField", true);
                builder.String("stringField", "Hello FlexBuffers");

                builder.Vector("vectorField", [&]() {
                    builder.Int(1);
                    builder.Int(2);
                    builder.Int(3);
                });

                builder.Map("mapField", [&]() {
                    builder.String("key1", "value1");
                    builder.Int("key2", 200);
                });
            });

            builder.Finish();

            return builder.GetBuffer();
        }

        /*
         * Vectors and Maps are very similar
         * A map is probably a type of vector
         */
        jsi::Value fromFlexValue(const flexbuffers::Reference &ref) {
            if (ref.IsInt() || ref.IsFloat() || ref.IsUInt()) {
                Log.Verbose("FlexInvoker: Double");
                return {ref.AsDouble()};
            }
            else if (ref.IsBool()) {
                Log.Verbose("FlexInvoker: Boolean");
                return {ref.AsBool()};
            }
            else if (ref.IsString()) {
                Log.Verbose("FlexInvoker: String");
                return getLink().createFromUTF8String(ref.AsString().str());
            }
            else if (ref.IsAnyVector() && !ref.IsMap()) {
                auto vector = ref.AsVector();
                auto array = jsi::Array(getLink().getRuntime(), vector.size());
                repeat (i, vector.size()) {
                    array.setValueAtIndex(getLink().getRuntime(), i, fromFlexValue(vector[i]));
                }
                return array;
            }
            else if (ref.IsMap()) {
                Log.Verbose("FlexInvoker: Map");
                auto map = ref.AsMap();
                auto object = jsi::Object(getLink().getRuntime());
                repeat(i, map.size()) {
                    auto key = map.Keys()[i].AsString();
                    auto value = fromFlexValue(map.Values()[i]);
                    Log.Verbose(string("FlexInvoker: Map: ") + key.c_str());
                    object.setProperty(getLink().getRuntime(), key.c_str(), value);
                }
                return object;
            }
            else return {};
        }


        jsi::Value testFlexBufferObject() {
            flexbuffers::Builder builder(1024);

            builder.Map([&] {
                builder.Int("intField", 42);
                builder.UInt("uintField", 123u);
                builder.Float("floatField", 3.14f);
                builder.Bool("boolField", true);
                builder.String("stringField", "Hello FlexBuffers");

                builder.Vector("vectorField", [&]() {
                    builder.Int(1);
                    builder.Int(2);
                    builder.Int(3);
                });

                builder.Map("mapField", [&]() {
                    builder.String("key1", "value1");
                    builder.Int("key2", 200);
                });
            });

            builder.Finish();

            auto root = flexbuffers::GetRoot(builder.GetBuffer());
            if (!root.IsMap()) throw ReaktorException("Not a map");
            return fromFlexValue(root);
        }

        Flow flow;
        std::atomic<long> count = 100;

        jsi::Value operator()(const char *name, const jsi::Value *args,
                              const FunctionDescriptor &descriptor) override {
//        auto argCount = descriptor.argumentCount;
            auto returnType = descriptor.returnType;


            if (returnType == DataType::FlowType) {
                flow = Flow();
                return flow.create();
            }

            if (returnType == DataType::Number) {
                flow.emit(make_shared<jsi::Value>(44));
                return 42;
            }

            if (returnType == DataType::Object) {
                Log.Verbose("FlexInvoker: DataType: Object");
                return testFlexBufferObject();
            }

            return {};
        }

        // Add TAGs for logging inside the PlatformInvoker
        explicit NetworkModule(std::shared_ptr<PlatformInvoker> platformInvoker, Logger log)
                : ReaktorHostObject(platformInvoker, log) {
            platformFunctions = {
                    descriptor("get", {
                            DataType::FlowType, {}
                    })
            };

            // Testing code
            nativeFunctions = {
                    descriptor("getFlow", {
                            DataType::FlowType, {}
                    }),
                    descriptor("getInt", {
                        DataType::Number, {}
                    }),
                    descriptor("getObject", {
                        DataType::Object, {}
                    })
            };
        }
    };
}  // namespace Reaktor
