#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
    typedef struct ReaktorByteBuffer {
        const uint8_t* data;
        int32_t size;
    } ReaktorByteBuffer;

    ReaktorByteBuffer Reaktor_FlexHello();
    void Reaktor_FreeByteBuffer(ReaktorByteBuffer buffer);
#ifdef __cplusplus
}
#endif
