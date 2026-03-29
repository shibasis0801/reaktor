#include "Reaktor.h"
#include <common/NativeFlexBuffer.h>
#include <cstdlib>
#include <cstring>

ReaktorByteBuffer Reaktor_FlexHello() {
    auto bytes = Reaktor_FlexHelloBytes();
    auto* data = static_cast<uint8_t*>(std::malloc(bytes.size()));
    if (data == nullptr || bytes.empty()) {
        return {nullptr, 0};
    }
    std::memcpy(data, bytes.data(), bytes.size());
    return {data, static_cast<int32_t>(bytes.size())};
}

void Reaktor_FreeByteBuffer(ReaktorByteBuffer buffer) {
    if (buffer.data != nullptr) {
        std::free(const_cast<uint8_t*>(buffer.data));
    }
}
