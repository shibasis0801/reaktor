#include <common/Invokable.h>
#include "Invokable.h"


flexbuffers::Reference Invokable::invokeSync(long flexPointer) {
    auto [data, size] = Flex_GetBuffer(flexPointer);
    auto root = flexbuffers::GetRoot(data, size);
    auto vector = root.AsVector();
    auto resultPointer = invokeSync(vector);
    auto [result, resultSize] = Flex_GetBuffer(resultPointer);
    return flexbuffers::GetRoot(result, resultSize);
};
