#pragma once

#include <darwin/DarwinBase.h>
#include <darwin/DarwinInvoker.h>
#include <modules/LayoutDatabase.h>


namespace Reaktor {
    jsi::Object createHostObject(
        NSObject *instance,
        const std::string &name
    ) {
       auto platformInvoker = make_shared<DarwinInvoker>(instance);
    
        if (name == "LayoutDatabase") {
            auto hostObject = make_shared<LayoutDatabase>(std::move(platformInvoker));
            return getLink().createFromHostObject(hostObject);
        }

        throw ReaktorException("Not Supported: " + name);
    }

    void addModule(
            NSObject *instance,
            const std::string &name
    ) {
        getLink().createGlobalProperty(name, createHostObject(instance, name));
    }
}
