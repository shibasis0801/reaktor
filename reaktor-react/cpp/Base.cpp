#include <Base.h>

// This is alive for the lifetime of the app, should be private
Reaktor::Link globalLink;

namespace Reaktor {
    ReaktorException::ReaktorException(std::string message): message(std::move(message)) {}
    const char *ReaktorException::what() const noexcept {
                return message.c_str();
    }

    Link& getLink() { return globalLink; }
    jsi::Runtime& getRuntime() { return getLink().getRuntime(); }

    void Link::install(
            jsi::Runtime *_runtime,
            std::shared_ptr<react::CallInvoker> _jsCallInvoker,
            std::shared_ptr<react::CallInvoker> _nativeCallInvoker
    ) {
        runtime = _runtime;
        jsCallInvoker = std::move(_jsCallInvoker);
        nativeCallInvoker = std::move(_nativeCallInvoker);
    }


    jsi::Runtime& Link::getRuntime() const {
        if (runtime == nullptr) throw ReaktorException("Null Runtime");
        return *runtime;
    }

    Link::~Link() {
        runtime = nullptr;
        jsCallInvoker = nullptr;
        nativeCallInvoker = nullptr;
    }

    jsi::PropNameID Link::createPropName(const std::string &propName) const {
        return jsi::PropNameID::forAscii(getRuntime(), propName);
    }

    jsi::Function Link::createFunction(
            const jsi::PropNameID &name,
            int argCount,
            jsi::HostFunctionType &&fn
    ) const {
        return jsi::Function::createFromHostFunction(getRuntime(), name, argCount, std::move(fn));
    }

    jsi::Function Link::createFunction(
            const std::string &name,
            int argCount,
            jsi::HostFunctionType &&fn
    ) const {
        return jsi::Function::createFromHostFunction(getRuntime(), createPropName(name), argCount, std::move(fn));
    }

    // Java
    jsi::Value Link::createFromUTF8String(const std::string &contents) const {
        return {
            getRuntime(),
            jsi::String::createFromUtf8(getRuntime(), contents)
        };
    }

    // C++
    jsi::Value Link::createFromAsciiString(const std::string &contents) const {
        return {
                getRuntime(),
                jsi::String::createFromAscii(getRuntime(), contents)
        };
    }


    void Link::createGlobalProperty(const std::string &name, jsi::Object object) const {
        getRuntime().global().setProperty(getRuntime(), createPropName(name), object);
    }

    jsi::Object Link::createFromHostObject(std::shared_ptr<jsi::HostObject> hostObject) const {
        return jsi::Object::createFromHostObject(getRuntime(), std::move(hostObject));
    }

    jsi::Value Link::getProperty(const std::string &name) const {
        return getRuntime().global().getProperty(getRuntime(), name.c_str());
    }

    std::string Link::getString(const jsi::Value &value) const {
        return value.asString(getRuntime()).utf8(getRuntime());
    }

    jsi::Function Link::getFunction(const string &name) const {
        return getRuntime().global().getPropertyAsFunction(getRuntime(), name.c_str());
    }
}
