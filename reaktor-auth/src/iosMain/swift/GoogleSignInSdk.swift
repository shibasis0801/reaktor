import Foundation
import UIKit
import GoogleSignIn
import app

final class GoogleSignInSdk: NSObject, AppleGoogleSignIn {
    var currentUser: AppleGoogleUser? { GIDSignIn.sharedInstance.currentUser.map(GoogleSdkUser.init) }
    func configure(clientId: String, serverClientId: String?) {
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId, serverClientID: serverClientId)
    }
    func signIn(controller: UIViewController) async throws -> any AppleGoogleUser {
        let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: controller)
        return GoogleSdkUser(result.user)
    }
    func restore() async throws -> any AppleGoogleUser {
        GoogleSdkUser(try await GIDSignIn.sharedInstance.restorePreviousSignIn())
    }
    func signOut() { GIDSignIn.sharedInstance.signOut() }
    func handleUrl(url: URL) -> Bool { GIDSignIn.sharedInstance.handle(url) }
}

private final class GoogleSdkUser: NSObject, AppleGoogleUser {
    private let user: GIDGoogleUser
    init(_ user: GIDGoogleUser) { self.user = user }
    var idToken: String? { user.idToken?.tokenString }
    var givenName: String? { user.profile?.givenName }
    var familyName: String? { user.profile?.familyName }
    var email: String? { user.profile?.email }
    func imageUrl(dimension: Int32) -> String? { user.profile?.imageURL(withDimension: UInt(dimension))?.absoluteString }
    func refresh() async throws -> any AppleGoogleUser {
        GoogleSdkUser(try await user.refreshTokensIfNeeded())
    }
}
