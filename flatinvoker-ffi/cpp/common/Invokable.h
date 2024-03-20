#pragma once

#include <common/CppBase.h>
#include <common/Flex.h>

struct Invokable {
    virtual long invokeSync(const flexbuffers::Vector &payload) = 0;
    inline flexbuffers::Reference invokeSync(long flexPointer);
};

