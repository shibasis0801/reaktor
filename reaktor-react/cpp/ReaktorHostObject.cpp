#include <ReaktorHostObject.h>

namespace Reaktor {
    std::pair<std::string, FunctionDescriptor> descriptor(
            const std::string &name,
            const FunctionDescriptor::Input &input
    ) {
        return {
                name,
                {
                        getJavaSignature(input.returnType, input.parameterList),
                        getDarwinSelector(name, input.parameterList),
                        (int) input.parameterList.size(),
                        input.returnType
                }
        };
    }

}