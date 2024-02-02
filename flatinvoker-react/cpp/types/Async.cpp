#include <types/Async.h>
namespace Reaktor {
    // AsyncMemory ----------------------------------------------
    // ----------------------------------------------------------
    Async* AsyncMemory::add(shared_ptr<Async> async) {
        const std::lock_guard guard(lock);
        Async *id = async.get();
        asyncMap[id] = async;
        return id;
    }

    void AsyncMemory::erase(Async *id) {
        const std::lock_guard guard(lock);
        auto async = asyncMap.find(id);
        if (async != asyncMap.end()) {
            auto str = getLink().createFromAsciiString("FLOW_STOPPED").asString(getRuntime());
        }


        asyncMap.erase(id);
    }

    // Async ----------------------------------------------------
    // ----------------------------------------------------------
    Async::~Async() = default;

    Async* Async::add(std::shared_ptr<Async> async) {
        return memory.add(async);
    }

    void Async::erase(Async *id) {
        memory.erase(id);
    }

    AsyncMemory Async::memory;

    // Flow ----------------------------------------------------
    // ---------------------------------------------------------

    void Flow::emit(shared_ptr<jsi::Value> value) {
        emitFn->call(getRuntime(), *value.get());
    }

    std::atomic<long> count = 100;
    jsi::Value Flow::create() {
        auto FlowClass = getRuntime().global().getPropertyAsFunction(getRuntime(), "Flow"); // getLink().getClass("Flow")
        auto flow = FlowClass.callAsConstructor(getRuntime());
        auto emit = flow.getObject(getRuntime()).getPropertyAsFunction(getRuntime(), "emit"); // class.getFunction("emit")
        emitFn = make_shared<jsi::Function>(std::move(emit));

        return flow;
    }

    void Flow::notify(shared_ptr<jsi::Value> value) {
        emit(value);
    }

    void Flow::stop() {
        // ToDo Call Native to stop the flow
    }

    // Promise ----------------------------------------------------
    // ------------------------------------------------------------

    jsi::Value Promise::create() {
        auto promiseClass = getLink().getFunction("Promise");
        return promiseClass.callAsConstructor(getRuntime(), getLink().createFunction(
                "executor",
                2,
                [&](jsi::Runtime &runtime, const jsi::Value &self, const jsi::Value *args, size_t argc) -> jsi::Value {

                    resolveFn = make_shared<jsi::Function>(args[0].asObject(runtime).asFunction(runtime));
                    rejectFn = make_shared<jsi::Function>(args[1].asObject(runtime).asFunction(runtime));

                    return {};
                }
        ));
    }

    void Promise::resolve(shared_ptr<jsi::Value> value) {
        getLink().jsCallInvoker->invokeAsync([=] {
            resolveFn->call(getRuntime(), *value.get());
            Async::erase(this);
        });
    }

    void Promise::reject(string &&reason) const {
//            getLink().jsCallInvoker->invokeAsync([=] {
//                rejectFn->call(getLink().getRuntime(), getLink().createFromAsciiString(reason));
//            });
    }

    void Promise::notify(shared_ptr<jsi::Value> value) {
        resolve(value);
    }
} // Reaktor