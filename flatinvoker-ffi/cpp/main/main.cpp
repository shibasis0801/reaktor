#include <common/Engine.h>
#include <iostream>

void flowLogic() {
    auto &runtime = *Engine::runtime;
    auto Flow = runtime.global().getPropertyAsFunction(runtime, "Flow");
    auto flow = Flow.callAsConstructor(runtime);

    auto collectFn = flow.getObject(runtime).getPropertyAsFunction(runtime, "collect");

    auto observerFn = Engine::createFunction(
            "test",
            1,
            [](facebook::jsi::Runtime& rt, const facebook::jsi::Value& thisValue,
               const facebook::jsi::Value* arguments, size_t count) -> facebook::jsi::Value {

                const auto &value = arguments[0];
                std::cout << value.asNumber() << std::endl;

                return {};
            }
    );
    collectFn.call(runtime, observerFn);

    auto emitFn = flow.getObject(runtime).getPropertyAsFunction(runtime, "emit");
    emitFn.call(runtime, 143);
}



int main() {
    int status = 0;
    try {
        Engine::start();
        flowLogic();
    } catch (facebook::jsi::JSError &e) {
        // Handle JS exceptions here.
        std::cerr << "JS Exception: " << e.getStack() << std::endl;
        status = 1;
    } catch (facebook::jsi::JSIException &e) {
        // Handle JSI exceptions here.
        std::cerr << "JSI Exception: " << e.what() << std::endl;
        status = 1;
    }

    return status;
}
