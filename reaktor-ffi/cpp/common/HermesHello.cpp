#include <common/HermesHello.h>
#include <cstring>
#include <hermes/hermes.h>
#include <jsi/jsi.h>
#include <string>

static char hermesResultBuffer[256] = {0};

const char* Reaktor_HermesHello() {
    try {
        auto runtimeConfig =
                hermes::vm::RuntimeConfig::Builder().withIntl(false).build();
        auto runtime = facebook::hermes::makeHermesRuntime(runtimeConfig);

        auto result = runtime->evaluateJavaScript(
                std::make_unique<facebook::jsi::StringBuffer>("'Hello from C++ (Hermes ' + (1 + 1) + ')'"),
                "hello.js");

        auto str = result.asString(*runtime).utf8(*runtime);
        strncpy(hermesResultBuffer, str.c_str(), sizeof(hermesResultBuffer) - 1);
        hermesResultBuffer[sizeof(hermesResultBuffer) - 1] = '\0';
        return hermesResultBuffer;
    } catch (...) {
        return "Hermes error";
    }
}
