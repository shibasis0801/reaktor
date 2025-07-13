#include <common/Flex.h>
#include <common/CppBase.h>

// This file should not be consumed by downstream. This is for Kotlin.
using namespace FlatInvoker;

long Flex_ParseJson(FlexPointer pointer, const char *jsonString) {
    auto start = high_resolution_clock::now();
    auto builder = FlexStore::Get(pointer);
    flatbuffers::Parser parser;

    parser.ParseFlexBuffer(jsonString, nullptr, builder);
    auto finish = high_resolution_clock::now();
    auto time = duration_cast<microseconds>(finish - start);

    return time.count();
    // todo unfortunately FlexBuffer calls Finish directly
//    Flex_Finish(pointer);
}

FlexPointer Flex_Create() {
    return FlexStore::Create();
}

void Flex_Destroy(FlexPointer pointer) {
    FlexStore::Destroy(pointer);
}

void Flex_Finish(FlexPointer pointer) {
    FlexStore::Finish(pointer);
}

FlexArray Flex_GetBuffer(FlexPointer pointer) {
    auto builder = FlexStore::Get(pointer);
    auto data = builder->GetBuffer().data();
    auto size = builder->GetSize();
    return { data, size };
}

void Flex_Null(FlexPointer pointer, const char* key) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->Null();
    } else {
        builder->Null(key);
    }
}

void Flex_Int(FlexPointer pointer, const char* key, FlexPointer value) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->Int(value);
    } else {
        builder->Int(key, value);
    }
}

void Flex_Float(FlexPointer pointer, const char* key, float value) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->Float(value);
    } else {
        builder->Float(key, value);
    }
}

void Flex_Double(FlexPointer pointer, const char* key, double value) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->Double(value);
    } else {
        builder->Double(key, value);
    }
}

void Flex_Bool(FlexPointer pointer, const char* key, bool value) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->Bool(value);
    } else {
        builder->Bool(key, value);
    }
}

void Flex_String(FlexPointer pointer, const char* key, const char* value) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->String(value);
    } else {
        builder->String(key, value);
    }
}

void Flex_Blob(FlexPointer pointer, const char* key, const uint8_t* value, size_t length) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        builder->Blob(value, length);
    } else {
        builder->Blob(key, value, length);
    }
}

size_t Flex_StartMap(FlexPointer pointer, const char* key) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        return builder->StartMap();
    } else {
        return builder->StartMap(key);
    }
}

void Flex_EndMap(FlexPointer pointer, size_t mapStart) {
    auto builder = FlexStore::Get(pointer);
    builder->EndMap(mapStart);
}

size_t Flex_StartVector(FlexPointer pointer, const char* key) {
    auto builder = FlexStore::Get(pointer);
    if (key == nullptr) {
        return builder->StartVector();
    } else {
        return builder->StartVector(key);
    }
}

void Flex_EndVector(FlexPointer pointer, size_t vectorStart) {
    auto builder = FlexStore::Get(pointer);
    builder->EndVector(vectorStart, false, false);
}












