#pragma once
#include <jsi/jsi.h>

using namespace facebook;
/*

TypeConverter (HostObject) should convert types from JSI to Flex
and from Flex to JSI
Similarly for Flutter, there should be a different abstraction


PlatformInvoker should only deal with FlexBuffers and platform invokations.


 */

struct FunctionDescriptor {
    std::string jvmSignature = ""; // is empty string on iOS
    std::string iosSelector = ""; // is empty string on android
    int argumentCount;
    std::string returnType;
};




template<class PlatformInvoker>
struct Invoker {
    jsi::Value operator()(
        const char *name,
        const jsi::Value *args,
        const FunctionDescriptor &descriptor
        ) {
        return static_cast<PlatformInvoker*>(this)->invoke(name, args, descriptor);
    }

    // You may need to nullify any references to native objects
    virtual ~Invoker() = default;
};
