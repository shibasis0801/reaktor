#include "Installer.h"
class AndroidInvoker1 : public Invoker<AndroidInvoker1> {
  public:
    jsi::Value invoke(
        const char *name,
        const jsi::Value *args,
        const FunctionDescriptor &descriptor
        ) {
        // JNI implementation
    }
};

