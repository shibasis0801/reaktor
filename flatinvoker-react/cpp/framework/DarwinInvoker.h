#include "Installer.h"

class DarwinInvoker1 : public Invoker<DarwinInvoker1> {
  public:
    jsi::Value invoke(
        const char *name,
        const jsi::Value *args,
        const FunctionDescriptor &descriptor
        ) {
        id data = NSObject;
        
    }
};