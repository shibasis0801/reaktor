#pragma once
#include <Base.h>
#include <ReaktorHostObject.h>
#include <types/Async.h>

namespace Reaktor {
struct LayoutDatabase : public ReaktorHostObject {
    explicit LayoutDatabase(
            std::shared_ptr<PlatformInvoker> platformInvoker, Logger log)
        : ReaktorHostObject(platformInvoker, log) {
        platformFunctions = {
            descriptor("getPage", {
                DataType::FlowType,{
                    {"uri", DataType::String},
                    {"parentPageUri", DataType::String},
                    //
                    {"headers", DataType::String},
                    {"body", DataType::String},
                    //
                    {"pageNumber", DataType::Number},
                    {"cacheKey", DataType::String},
                    {"pageContextCacheKey", DataType::String},
                    //
                    {"forceFetch", DataType::Boolean},
                    {"isBackNavigation", DataType::Boolean}
                }
            }),
        };
    }
};
}
