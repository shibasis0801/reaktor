import Foundation
import FirebaseCore
import app

final class FirebaseSdk: NSObject, AppleFirebaseSdk {
    var configured: Bool { FirebaseApp.app() != nil }
    func configure() { FirebaseApp.configure() }
}
