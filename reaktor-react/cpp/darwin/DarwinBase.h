#pragma once

#import <Foundation/Foundation.h>
#include <Base.h>

namespace Reaktor {
    struct DarwinLogger: public Logger {
        DarwinLogger(const std::string &TAG);
        void Verbose(const string &message) override;
        void Error(const string &errorMessage) override;
    };

    static DarwinLogger Log("Reaktor");
}
