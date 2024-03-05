#include <common/Flex.h>
#include <common/CppBase.hpp>
#include <flatbuffers/flexbuffers.h>
#include <flatbuffers/idl.h>
#include <flatbuffers/buffer.h>

/*
 * Optimise all these, inline what you can.
 */
struct BuilderInfo {
    bool isFinished = false;
};

class FlexBufferStore {
    // Merge ?
    std::unordered_map<uintptr_t, std::shared_ptr<flexbuffers::Builder>> builders;
    std::unordered_map<uintptr_t, BuilderInfo> builderInfo;

public:
    uintptr_t Create() {
        auto builder = std::make_shared<flexbuffers::Builder>(1024);
        auto pointer = reinterpret_cast<uintptr_t>(builder.get());
        builders[pointer] = builder;
        builderInfo[pointer] = {.isFinished = false};
        return pointer;
    }

    // Also need to destroy when builder goes out of scope in parent language
    void Destroy(uintptr_t pointer) {
        auto builder = Get(pointer);
        builder->Clear();
        builders.erase(pointer);
        builderInfo.erase(pointer);
    }

    shared_ptr<flexbuffers::Builder> Get(uintptr_t pointer) {
        if (!builders.contains(pointer)) return nullptr;
        return builders[pointer];
    }

    inline shared_ptr<flexbuffers::Builder> GetOrThrow(uintptr_t pointer) {
        auto builder = Get(pointer);
        GUARD_THROW(builder, "No FlexBuilder found");
        return builder;
    }

    bool IsFinished(uintptr_t pointer) {
        if (!builderInfo.contains(pointer)) return false;
        return builderInfo[pointer].isFinished;
    }

    void Finish(uintptr_t pointer) {
        auto builder = GetOrThrow(pointer);
        if (IsFinished(pointer)) return;
        // log double finish error

        builderInfo[pointer].isFinished = true;
        builder->Finish();
    }

} FlexStore;

void Flex_ParseJson(uintptr_t pointer, const char *jsonString) {
    auto builder = FlexStore.Get(pointer);
    flatbuffers::Parser parser;
    parser.ParseFlexBuffer(jsonString, nullptr, builder.get());
    // todo unfortunately FlexBuffer calls Finish directly
    Flex_Finish(pointer);
}

uintptr_t Flex_Create() {
    return FlexStore.Create();
}

void Flex_Destroy(uintptr_t pointer) {
    FlexStore.Destroy(pointer);
}

void Flex_Finish(uintptr_t pointer) {
    FlexStore.Finish(pointer);
}

FlexArray Flex_GetBuffer(uintptr_t pointer) {
    auto builder = FlexStore.GetOrThrow(pointer);
    return { builder->GetBuffer().data(), builder->GetSize() };
}

void Flex_Null(uintptr_t pointer, const char* key) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Null();
    } else {
        builder->Null(key);
    }
}

void Flex_Int(uintptr_t pointer, const char* key, int64_t value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Int(value);
    } else {
        builder->Int(key, value);
    }
}

void Flex_Float(uintptr_t pointer, const char* key, float value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Float(value);
    } else {
        builder->Float(key, value);
    }
}

void Flex_Double(uintptr_t pointer, const char* key, double value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Double(value);
    } else {
        builder->Double(key, value);
    }
}

void Flex_Bool(uintptr_t pointer, const char* key, bool value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Bool(value);
    } else {
        builder->Bool(key, value);
    }
}

void Flex_String(uintptr_t pointer, const char* key, const char* value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->String(value);
    } else {
        builder->String(key, value);
    }
}

void Flex_Blob(uintptr_t pointer, const char* key, const uint8_t* value, size_t length) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Blob(value, length);
    } else {
        builder->Blob(key, value, length);
    }
}

size_t Flex_StartMap(uintptr_t pointer, const char* key) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        return builder->StartMap();
    } else {
        return builder->StartMap(key);
    }
}

void Flex_EndMap(uintptr_t pointer, size_t mapStart) {
    auto builder = FlexStore.GetOrThrow(pointer);
    builder->EndMap(mapStart);
}

size_t Flex_StartVector(uintptr_t pointer, const char* key) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        return builder->StartVector();
    } else {
        return builder->StartVector(key);
    }
}

void Flex_EndVector(uintptr_t pointer, size_t vectorStart) {
    auto builder = FlexStore.GetOrThrow(pointer);
    builder->EndVector(vectorStart, false, false);
}












