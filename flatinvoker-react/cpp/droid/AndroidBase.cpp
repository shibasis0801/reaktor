#include <droid/AndroidBase.h>

namespace Reaktor {
    void throwIfJNIError(const std::string &location) {
        try {
            jni::throwPendingJniExceptionAsCppException();
        } catch (const jni::JniException &exception) {
            auto message = location + ": JNI Exception thrown in AndroidInvoker, aborting";
            Log.Error(message);
            throw ReaktorException(message);
        }
    }

    void AndroidLogger::Verbose(const std::string &message) {
        __android_log_print(ANDROID_LOG_VERBOSE, TAG.c_str(), "%s", message.c_str());
    }

    void AndroidLogger::Error(const std::string &errorMessage) {
        __android_log_print(ANDROID_LOG_ERROR, TAG.c_str(), "%s", errorMessage.c_str());
    }

    AndroidLogger::AndroidLogger(const std::string &TAG): Logger(TAG) {}

}