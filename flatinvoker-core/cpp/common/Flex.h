#pragma once

#include <common/CBase.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const uint8_t* buffer;
    size_t size;
} FlexArray;

// Create a shared_ptr<FlexBufferBuilder>, store it in FlexBufferStore, return the pointer as a uintptr_t
// use unique_ptr ?
uintptr_t Flex_Create();

void Flex_ParseJson(uintptr_t pointer, const char* jsonString);

// Clear and delete the builder
void Flex_Destroy(uintptr_t pointer);

// Finish the flexbuffer -> Ready for transfer
void Flex_Finish(uintptr_t pointer);

// Get the Buffer. Must be called after Flex_Finish
FlexArray Flex_GetBuffer(uintptr_t pointer);

// Value functions have a key, if they are inside a Map
// Implementations must call the relevant actual function based on null


// Not complete implementation, yet
// Target is to achieve parity with the JsonParser in FlexBuffers
// So that any JSON returned by it can be used by Kotlin Serialization

// FlatBuffers repo will be added with commit hash
// A comprehensive test suite is needed to check when upgrades happen

// Need to read the full FlexBuffer source auintptr_t with the JSONParser

// key can be null
void Flex_Null(uintptr_t pointer, const char* key);
void Flex_Int(uintptr_t pointer, const char* key, int64_t value);
void Flex_Float(uintptr_t pointer, const char* key, float value);
void Flex_Double(uintptr_t pointer, const char* key, double value);
void Flex_Bool(uintptr_t pointer, const char* key, bool value);
void Flex_String(uintptr_t pointer, const char* key, const char* value);
void Flex_Blob(uintptr_t pointer, const char* key, const uint8_t* value, size_t length);
size_t Flex_StartMap(uintptr_t pointer, const char* key);
void Flex_EndMap(uintptr_t pointer, size_t mapStart);
size_t Flex_StartVector(uintptr_t pointer, const char* key);
void Flex_EndVector(uintptr_t pointer, size_t vectorStart);

#ifdef __cplusplus
}
#endif
