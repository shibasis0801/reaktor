#include <common/Engine.h>

static std::optional<std::string> readFile(const char *path) {
    auto absolutePath = std::filesystem::absolute(path);
    std::cout << "Reading " << absolutePath << "\n" ;
    std::ifstream fileStream(absolutePath);
    std::stringstream stringStream;

    if (fileStream) {
        stringStream << fileStream.rdbuf();
        fileStream.close();
    } else throw std::runtime_error("File not found");

    return stringStream.str();
}
unique_ptr<HermesRuntime> Engine::runtime = nullptr;
void Engine::start() {
    auto runtimeConfig =
            hermes::vm::RuntimeConfig::Builder().withIntl(false).build();
    runtime = facebook::hermes::makeHermesRuntime(runtimeConfig);
    const auto path = "../../js/Flow.js";
    auto code = readFile(path);
    runtime->evaluateJavaScript(
            std::make_unique<facebook::jsi::StringBuffer>(*code), path);
}


jsi::PropNameID Engine::createPropName(const std::string &propName) {
    return jsi::PropNameID::forAscii(*runtime, propName);
}

jsi::Function Engine::createFunction(
        const jsi::PropNameID &name,
        int argCount,
        jsi::HostFunctionType &&fn
) {
    return jsi::Function::createFromHostFunction(*runtime, name, argCount, std::move(fn));
}

jsi::Function Engine::createFunction(
        const std::string &name,
        int argCount,
        jsi::HostFunctionType &&fn
) {
    return jsi::Function::createFromHostFunction(*runtime, createPropName(name), argCount, std::move(fn));
}

// Java
jsi::Value Engine::createFromUTF8String(const std::string &contents) {
    return {
            *runtime,
            jsi::String::createFromUtf8(*runtime, contents)
    };
}

// C++
jsi::Value Engine::createFromAsciiString(const std::string &contents) {
    return {
            *runtime,
            jsi::String::createFromAscii(*runtime, contents)
    };
}


void Engine::createGlobalProperty(const std::string &name, jsi::Object object) {
    runtime->global().setProperty(*runtime, createPropName(name), object);
}

jsi::Object Engine::createFromHostObject(std::shared_ptr<jsi::HostObject> hostObject) {
    return jsi::Object::createFromHostObject(*runtime, std::move(hostObject));
}

jsi::Value Engine::getProperty(const std::string &name) {
    return runtime->global().getProperty(*runtime, name.c_str());
}

std::string Engine::getString(const jsi::Value &value) {
    return value.asString(*runtime).utf8(*runtime);
}

jsi::Function Engine::getFunction(const string &name) {
    return runtime->global().getPropertyAsFunction(*runtime, name.c_str());
}
