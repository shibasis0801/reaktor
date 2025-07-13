#pragma once

#include <Base.h>
/**

Data Types
 Number     => jsi::Number
 String     => jsi::String
 Array      => jsi::Array
 Map        => jsi::Object

 Boolean     => jsi::Boolean
 ArrayBuffer => jsi::ArrayBuffer
 Function    => jsi::Function

Data Holders
 jsi::Value
 Promise<jsi::Value>
 Observable<jsi::Value>

Write these types and you don't need the React definitions
 1. String
 2. Array<String>
 3. Promise<String>
 4. Promise<Array<String>>
 5. Number
 6. Map<String, String>
 7. Observable<String>
 8. Observable<Map<String, String>>

All these interfaces will be declared in Kotlin
These interfaces will be implemented by Android(fbjni) / iOS(objC)
That way we can inject dependencies into KMM without adding React

 Other supporting interfaces needed
 1. Default data to be sent from Kotlin to C++ for others to write JSI bindings
 2. NetworkInterface
 3. BackgroundSchedulerInterface
 4. LRU Interface


 */
namespace Reaktor {
    enum DataType {
        Undefined,
        Boolean,
        Number,
        String,
        Object,
        Array,
        PromiseType,
        FlowType,
        NoArgFn,
        SingleArgFn
    };

    using Field = std::variant<
        jsi::Object,
        double,
        std::string
    >;

    struct TypeDescriptor {
        std::string name;
        DataType type;
    };

    struct InstanceVariable {
        Field field;
        DataType type;
    };

    using InstanceDescriptor = std::vector<TypeDescriptor>;

    using Instance = unordered_map<std::string, InstanceVariable>;

    using FunctionParamList = std::initializer_list<std::pair<std::string, DataType>>;

    std::string getJavaSignature(
        DataType returnType,
        FunctionParamList parameterTypes
    );

    std::string getDarwinSelector(
            const std::string &functionName,
            FunctionParamList parameterTypes
    );
}
