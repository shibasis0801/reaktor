#pragma once
#include <droid/AndroidBase.h>

namespace Reaktor {
    struct Async;

    class AsyncMemory {
        // Using the address of the pointer as the key
        std::unordered_map<Async*, std::shared_ptr<Async>> asyncMap;
        std::mutex lock;

    public:
        Async* add(std::shared_ptr<Async> async);
        void erase(Async *id);
    };

    struct Async {
        virtual jsi::Value create() = 0;
        virtual void notify(std::shared_ptr<jsi::Value> value) = 0;
        virtual ~Async();

        static Async* add(std::shared_ptr<Async> async);
        static void erase(Async *id);

    private:
        static AsyncMemory memory;
    };

    struct Promise: public Async {
        void notify(shared_ptr<jsi::Value> value) override;
        jsi::Value create() override;

        void resolve(shared_ptr<jsi::Value> value);
        void reject(std::string &&reason) const;

    private:
        shared_ptr<jsi::Function> resolveFn;
        shared_ptr<jsi::Function> rejectFn;
    };


    struct Flow: Async {
        void notify(shared_ptr<jsi::Value> value) override;
        jsi::Value create() override;

        void emit(shared_ptr<jsi::Value> value);
        void stop();

    private:
        shared_ptr<jsi::Function> emitFn;
    };
}
