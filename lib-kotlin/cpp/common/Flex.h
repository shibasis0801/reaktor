#pragma once


#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const uint8_t* buffer;
    size_t size;
} FlexArray;

int64_t Flex_getInt(long pointer);
long Flex_new();
void Flex_delete(long pointer);
FlexArray Flex_getBuffer(long pointer);
FlexArray Flex_getBuffer1(long pointer);


#ifdef __cplusplus
}
#endif