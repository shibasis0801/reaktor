#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
    int reaktorTest();
    const char* getName();
    char* getNameCpp();
    int sendByteArray(const uint8_t* bytes, int size);
#ifdef __cplusplus
}
#endif
