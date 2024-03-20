#include <common/CppBase.h>

namespace FlatInvoker {
    Exception::Exception(std::string message): message(std::move(message)) {}
    const char *Exception::what() const noexcept {
        return message.c_str();
    }

    std::unique_ptr<flexbuffers::Builder> builder = std::make_unique<flexbuffers::Builder>(1024);
    FlexPointer FlexStore::Create() {
        builder->Clear();
        auto pointer = reinterpret_cast<FlexPointer>(builder.get());
        return pointer;
    }

    void FlexStore::Destroy(FlexPointer pointer) {
        FlexStore::Get(pointer)->Clear();
    }

    void FlexStore::Finish(FlexPointer pointer) {
        Get(pointer)->Finish();
    }

    flexbuffers::Builder *FlexStore::Get(FlexPointer pointer) {
        return reinterpret_cast<flexbuffers::Builder *>(pointer);
    }
} // namespace FlatInvoker
