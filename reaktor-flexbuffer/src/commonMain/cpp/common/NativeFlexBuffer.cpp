#include <common/NativeFlexBuffer.h>
#include <flatbuffers/flexbuffers.h>

std::vector<uint8_t> Reaktor_FlexHelloBytes() {
    flexbuffers::Builder builder(256);
    builder.Map([&] {
        builder.String("message", "Hello from FlexBuffer C++");
        builder.Int("answer", 42);
    });
    builder.Finish();
    return builder.GetBuffer();
}
