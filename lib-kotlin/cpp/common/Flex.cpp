#include <common/Flex.h>
#include <common/Base.h>
#include <flatbuffers/flexbuffers.h>

struct FlexBufferStore {
    std::unordered_map<long, std::shared_ptr<flexbuffers::Builder>> builders;
    long create() {
        auto builder = std::make_shared<flexbuffers::Builder>(1024);
        auto pointer = reinterpret_cast<long>(builder.get());
        builder->Vector([=]() {
            builder->Int(42);
            builder->String("Shibasis");
        });
        builders[pointer] = builder;
        return pointer;
    }

    void destroy(long pointer) {
        builders.erase(pointer);
    }

    shared_ptr<flexbuffers::Builder> get(long pointer) {
        if (!builders.contains(pointer)) throw Reaktor::ReaktorException("FlexBuffer not found");
        return builders[pointer];
    }
} FlexBufferStorage;

long Flex_new() {
    return FlexBufferStorage.create();
}

void Flex_delete(long pointer) {
    FlexBufferStorage.destroy(pointer);
}

vector<uint8_t> data = { 1, 2, 3, 4, 5};
uint8_t elements[] = { 1, 2, 3, 4, 5 };
uint8_t* elements1 = data.data();

/*
 * todo fix
 * as long as flexbufferbuilder is alive, this vector will be present.
 * but we need to move or copy it
 */
FlexArray Flex_getBuffer(long pointer) {
//    auto buffer = FlexBufferStorage.getBuffer(pointer);
//    for (auto x: buffer)
//        printf("%x ", x);
//    return {buffer.data(), buffer.size()};

    return {elements, 5};
}

FlexArray Flex_getBuffer1(long pointer) {
    auto builder = FlexBufferStorage.get(pointer);
    builder->Finish();
    return { builder->GetBuffer().data(), builder->GetSize() };
}

int64_t Flex_getInt(long pointer) {
    auto builder = FlexBufferStorage.get(pointer);
    auto root = flexbuffers::GetRoot(builder->GetBuffer());
    return root.AsVector()[0].AsInt64();
}


