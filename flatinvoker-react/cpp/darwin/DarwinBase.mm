#import <darwin/DarwinBase.h>

namespace Reaktor {
    DarwinLogger::DarwinLogger(const std::string &TAG): Logger(TAG)
    {}
   
    void DarwinLogger::Verbose(const string &message) {
        NSLog(@"%s: %s", TAG.c_str(), message.c_str());
    }
    
    void DarwinLogger::Error(const string &errorMessage) {
        NSLog(@"%s: %s", TAG.c_str(), errorMessage.c_str());
    }
}

