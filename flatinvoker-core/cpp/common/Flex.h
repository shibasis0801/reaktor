#pragma once

#include <common/CBase.h>

typedef int64_t FlexPointer;

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const uint8_t* buffer;
    size_t size;
} FlexArray;

FlexPointer Flex_Create();
void Flex_ParseJson(FlexPointer pointer, const char* jsonString);
void Flex_Destroy(FlexPointer pointer);
void Flex_Finish(FlexPointer pointer);
FlexArray Flex_GetBuffer(FlexPointer pointer);
void Flex_Null(FlexPointer pointer, const char* key);
void Flex_Int(FlexPointer pointer, const char* key, FlexPointer value);
void Flex_Float(FlexPointer pointer, const char* key, float value);
void Flex_Double(FlexPointer pointer, const char* key, double value);
void Flex_Bool(FlexPointer pointer, const char* key, bool value);
void Flex_String(FlexPointer pointer, const char* key, const char* value);
void Flex_Blob(FlexPointer pointer, const char* key, const uint8_t* value, size_t length);
size_t Flex_StartMap(FlexPointer pointer, const char* key);
void Flex_EndMap(FlexPointer pointer, size_t mapStart);
size_t Flex_StartVector(FlexPointer pointer, const char* key);
void Flex_EndVector(FlexPointer pointer, size_t vectorStart);

#ifdef __cplusplus
}
#endif
