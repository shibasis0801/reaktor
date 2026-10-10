import Foundation
import FirebaseCrashlytics
import app

final class CrashlyticsSdk: NSObject, AppleCrashReporter {
    func recordException(error: any Error) { Crashlytics.crashlytics().record(error: error) }
    func log(message: String) { Crashlytics.crashlytics().log(message) }
    func setUserId(userId_: String) { Crashlytics.crashlytics().setUserID(userId_) }
    func setCustomKey(key: String, value: String) { Crashlytics.crashlytics().setCustomValue(value, forKey: key) }
}
