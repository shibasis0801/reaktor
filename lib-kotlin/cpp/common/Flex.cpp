#include <common/Flex.h>
#include <common/Base.h>
#include <flatbuffers/flexbuffers.h>

/*
 * Optimise all these, inline what you can.
 */
struct BuilderInfo {
    bool isFinished = false;
};

class FlexBufferStore {
    // Merge ?
    std::unordered_map<long, std::shared_ptr<flexbuffers::Builder>> builders;
    std::unordered_map<long, BuilderInfo> builderInfo;

public:
    long Create() {
        auto builder = std::make_shared<flexbuffers::Builder>(1024);
        auto pointer = reinterpret_cast<long>(builder.get());
        builders[pointer] = builder;
        builderInfo[pointer] = {.isFinished = false};
        return pointer;
    }

    // Also need to destroy when builder goes out of scope in parent language
    void Destroy(long pointer) {
        auto builder = Get(pointer);
        builder->Clear();
        builders.erase(pointer);
        builderInfo.erase(pointer);
    }

    shared_ptr<flexbuffers::Builder> Get(long pointer) {
        if (!builders.contains(pointer)) return nullptr;
        return builders[pointer];
    }

    inline shared_ptr<flexbuffers::Builder> GetOrThrow(long pointer) {
        auto builder = Get(pointer);
        GUARD_THROW(builder, "No FlexBuilder found");
        return builder;
    }

    bool IsFinished(long pointer) {
        if (!builderInfo.contains(pointer)) return false;
        return builderInfo[pointer].isFinished;
    }

    void Finish(long pointer) {
        auto builder = GetOrThrow(pointer);
        if (IsFinished(pointer)) return;
        // log double finish error

        builderInfo[pointer].isFinished = true;
        builder->Finish();
    }

} FlexStore;

long Flex_Create() {
    return FlexStore.Create();
}

void Flex_Destroy(long pointer) {
    FlexStore.Destroy(pointer);
}

void Flex_Finish(long pointer) {
    FlexStore.Finish(pointer);
}

FlexArray Flex_GetBuffer(long pointer) {
    Flex_Finish(pointer);
    auto builder = FlexStore.GetOrThrow(pointer);
    return { builder->GetBuffer().data(), builder->GetSize() };
}

void Flex_Null(long pointer, const char* key) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Null();
    } else {
        builder->Null(key);
    }
}

void Flex_Int(long pointer, const char* key, int64_t value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Int(value);
    } else {
        builder->Int(key, value);
    }
}

void Flex_Float(long pointer, const char* key, float value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Float(value);
    } else {
        builder->Float(key, value);
    }
}

void Flex_Double(long pointer, const char* key, double value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Double(value);
    } else {
        builder->Double(key, value);
    }
}

void Flex_Bool(long pointer, const char* key, bool value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Bool(value);
    } else {
        builder->Bool(key, value);
    }
}

void Flex_String(long pointer, const char* key, const char* value) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->String(value);
    } else {
        builder->String(key, value);
    }
}

void Flex_Blob(long pointer, const char* key, const uint8_t* value, size_t length) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        builder->Blob(value, length);
    } else {
        builder->Blob(key, value, length);
    }
}

size_t Flex_StartMap(long pointer, const char* key) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        return builder->StartMap();
    } else {
        return builder->StartMap(key);
    }
}

void Flex_EndMap(long pointer, size_t mapStart) {
    auto builder = FlexStore.GetOrThrow(pointer);
    builder->EndMap(mapStart);
}

size_t Flex_StartVector(long pointer, const char* key) {
    auto builder = FlexStore.GetOrThrow(pointer);
    if (key == nullptr) {
        return builder->StartVector();
    } else {
        return builder->StartVector(key);
    }
}

void Flex_EndVector(long pointer, size_t vectorStart) {
    auto builder = FlexStore.GetOrThrow(pointer);
    builder->EndVector(vectorStart, false, false);
}












