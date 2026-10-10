import Foundation
import FirebaseMessaging
import app

final class FirebaseMessagingSdk: NSObject, AppleRemoteMessaging, MessagingDelegate {
    func attachDelegate() { Messaging.messaging().delegate = self }
    func autoInit(enabled: Bool) { Messaging.messaging().isAutoInitEnabled = enabled }
    func setApnsToken(token: Foundation.Data) { Messaging.messaging().apnsToken = token }
    func requestToken() async throws -> String { try await Messaging.messaging().token() }
    func deleteToken() async throws { try await Messaging.messaging().deleteToken() }
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken token: String?) {
        AppleRemoteMessagingRuntime.shared.didReceiveToken(token: token)
    }
}
