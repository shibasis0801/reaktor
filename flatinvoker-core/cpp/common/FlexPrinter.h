#pragma once

#include <common/CppBase.h>
#include <iostream>
#include <flatbuffers/flexbuffers.h>

namespace flexbuffers {
    void PrintIndent(size_t indent) {
        for (size_t i = 0; i < indent; ++i) {
            std::cout << "  ";
        }
    }

    std::string enumToString(Type value) {
        static const std::unordered_map<Type, const char*> enumNames = {
                {FBT_NULL, "FBT_NULL"},
                {FBT_INT, "FBT_INT"},
                {FBT_UINT, "FBT_UINT"},
                {FBT_FLOAT, "FBT_FLOAT"},
                {FBT_KEY, "FBT_KEY"},
                {FBT_STRING, "FBT_STRING"},
                {FBT_INDIRECT_INT, "FBT_INDIRECT_INT"},
                {FBT_INDIRECT_UINT, "FBT_INDIRECT_UINT"},
                {FBT_INDIRECT_FLOAT, "FBT_INDIRECT_FLOAT"},
                {FBT_MAP, "FBT_MAP"},
                {FBT_VECTOR, "FBT_VECTOR"},
                {FBT_VECTOR_INT, "FBT_VECTOR_INT"},
                {FBT_VECTOR_UINT, "FBT_VECTOR_UINT"},
                {FBT_VECTOR_FLOAT, "FBT_VECTOR_FLOAT"},
                {FBT_VECTOR_KEY, "FBT_VECTOR_KEY"},
                {FBT_VECTOR_STRING_DEPRECATED, "FBT_VECTOR_STRING_DEPRECATED"},
                {FBT_VECTOR_INT2, "FBT_VECTOR_INT2"},
                {FBT_VECTOR_UINT2, "FBT_VECTOR_UINT2"},
                {FBT_VECTOR_FLOAT2, "FBT_VECTOR_FLOAT2"},
                {FBT_VECTOR_INT3, "FBT_VECTOR_INT3"},
                {FBT_VECTOR_UINT3, "FBT_VECTOR_UINT3"},
                {FBT_VECTOR_FLOAT3, "FBT_VECTOR_FLOAT3"},
                {FBT_VECTOR_INT4, "FBT_VECTOR_INT4"},
                {FBT_VECTOR_UINT4, "FBT_VECTOR_UINT4"},
                {FBT_VECTOR_FLOAT4, "FBT_VECTOR_FLOAT4"},
                {FBT_BLOB, "FBT_BLOB"},
                {FBT_BOOL, "FBT_BOOL"},
                {FBT_VECTOR_BOOL, "FBT_VECTOR_BOOL"},
                {FBT_MAX_TYPE, "FBT_MAX_TYPE"}
        };

        auto it = enumNames.find(value);
        return it->second;
    }

    void PrintBuffer(const std::vector<uint8_t>& buffer) {
        std::cout << "FlexBuffer Bytes (" << buffer.size() << " bytes):" << std::endl;
        for (size_t i = 0; i < buffer.size(); ++i) {
            if (i % 16 == 0) {
                std::cout << std::endl << std::setw(4) << std::setfill('0') << i << ": ";
            }
            std::cout << std::setw(2) << std::setfill('0') << std::hex << static_cast<int>(buffer[i]) << " ";
        }
        std::cout << std::dec << std::endl; // Reset number format
    }

    vector<uint8_t> bytes(const std::string &str) {
        vector<uint8_t> values(str.size());
        repeat(i, str.size()) {
            values[i] = str[i];
        }
        return values;
    }

    void buildMap(flexbuffers::Builder &builder) {
        builder.Map([&]() {
            builder.Int("answer", 42);
        });
    }

    // 2a 04 01
    void buildInt(flexbuffers::Builder &builder) {
        builder.Int(42);
    }

    // 01 2a 04 02 28 01
    // 02 2a 2b 04 04 04 28 01
    // 03 2a 2b 2c 04 04 04 06 28 01
    void buildIntVector(flexbuffers::Builder &builder) {
        builder.Vector([&] {
            repeat(i, 3) {
                builder.Int(42  + i);
            }
        });
    }

    // 01 2a 01 2c 01
    // 02 2a 2b 02 2c 01
    // 03 2a 2b 2c 03 2c 01
    void buildTypedIntVector(flexbuffers::Builder &builder) {
        builder.TypedVector([&] {
            repeat(i, 3) {
                builder.Int(42  + i);
            }
        });
    }



    void testPrintFlex() {
        auto builder = flexbuffers::Builder();

//        buildMap(builder);
//        buildInt(builder);
//        buildIntVector(builder);
        buildTypedIntVector(builder);

        builder.Finish();

//        PrintBuffer(bytes("answer"));

        PrintBuffer(builder.GetBuffer());
    }
}

