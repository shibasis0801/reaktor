#include <types/Types.h>

namespace Reaktor {
// https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html
std::unordered_map<DataType, std::string> typeToJniSignature{
    {DataType::Undefined, "V"},
    {DataType::Boolean, "Z"},
    {DataType::Number,"D"}, // also allow conversion from int/others to double ?
    {DataType::String, "Ljava/lang/String;"},
    {DataType::Object, "Ljava/util/HashMap;"},
    {DataType::Array, "Ljava/util/ArrayList;"},
    {DataType::PromiseType, "Ldev/shibasis/flatinvoker/react/types/Promise;"},
    {DataType::FlowType, "Ldev/shibasis/flatinvoker/react/types/FlowHandle;"},
    {DataType::SingleArgFn, "Ldev/shibasis/flatinvoker/react/types/SingleArgNativeFunction;"}
};

// JNI doesn't care about the names of the parameters, but we need them for the
// Darwin selector
std::string getJavaSignature(DataType returnType,
                             FunctionParamList parameterTypes) {
    std::string signature = "(";
    for (auto [_, parameterType] : parameterTypes) {
        if (typeToJniSignature.contains(parameterType)) {
            signature += typeToJniSignature[parameterType];
        } else {
            throw ReaktorException("Invalid parameter type");
        }
    }
    signature += ")";
    if (typeToJniSignature.contains(returnType)) {
        signature += typeToJniSignature[returnType];
    } else {
        throw ReaktorException("Invalid return type"); // didn't come to logcat :/
    }

    return signature;
}

std::string getDarwinSelector(const std::string &functionName,
                              FunctionParamList parameterTypes) {
    std::string selector = functionName;
    // 1 -> understand Selector syntax in objc
    // 2 -> understand name translation for functions in kotlin/native
    // 3 -> with the remaining cells inside your head, build the selector string
    int i = 0;
    for (auto [name, _] : parameterTypes) {
        if (i == 0) {
            selector += std::toupper(name[0]);
            selector += name.substr(1);
        } else {
            selector += name;
        }
        selector += ":";
        i++;
    }
    return selector;
}
}  // namespace Reaktor
