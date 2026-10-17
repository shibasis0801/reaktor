import Foundation
import UIKit
import AppAuth
import GTMAppAuth
import app

final class GoogleSignInSdk: NSObject, AppleGoogleOAuthTransport {
    private let store = KeychainStore(itemName: "auth")
    private var authorizationFlow: OIDExternalUserAgentSession?

    func authorize(request: GoogleOAuthRequest, controller: UIViewController, completion: @escaping (AppleGoogleOAuthSession?, Error?) -> Void) {
        let configuration = OIDServiceConfiguration(
            authorizationEndpoint: URL(string: request.authorizationEndpoint)!,
            tokenEndpoint: URL(string: request.tokenEndpoint)!
        )
        let tokenParameters = request.tokenParameters
        let request = OIDAuthorizationRequest(
            configuration: configuration, clientId: request.clientId,
            scopes: request.scopes, redirectURL: URL(string: request.redirectUrl)!,
            responseType: OIDResponseTypeCode, additionalParameters: request.authorizationParameters
        )
        authorizationFlow = OIDAuthorizationService.present(request, presenting: controller) { response, error in
            self.authorizationFlow = nil
            guard let response = response,
                  let tokenRequest = response.tokenExchangeRequest(withAdditionalParameters: tokenParameters) else {
                completion(nil, error ?? NSError(domain: "GoogleSignIn", code: 2))
                return
            }
            OIDAuthorizationService.perform(tokenRequest, originalAuthorizationResponse: response) { tokenResponse, error in
                guard let tokenResponse = tokenResponse else {
                    completion(nil, error ?? NSError(domain: "GoogleSignIn", code: 3))
                    return
                }
                do {
                    let state = OIDAuthState(authorizationResponse: response, tokenResponse: tokenResponse)
                    let session = AuthSession(authState: state)
                    try self.store.save(authSession: session)
                    let user = GoogleSdkUser(session, store: self.store)
                    completion(user, nil)
                } catch {
                    completion(nil, error)
                }
            }
        }
    }

    func restore(completion: @escaping (AppleGoogleOAuthSession?, Error?) -> Void) {
        do {
            let session = try store.retrieveAuthSession()
            completion(GoogleSdkUser(session, store: store), nil)
        } catch {
            completion(nil, error)
        }
    }

    func clear() {
        try? store.removeAuthSession()
    }

    func handleUrl(url: URL) -> Bool {
        authorizationFlow?.resumeExternalUserAgentFlow(with: url) ?? false
    }
}

private final class GoogleSdkUser: NSObject, AppleGoogleOAuthSession {
    private let session: AuthSession
    private let store: KeychainStore
    init(_ session: AuthSession, store: KeychainStore) {
        self.session = session
        self.store = store
    }
    var idToken: String? { session.authState.lastTokenResponse?.idToken }
    var clientId: String { session.authState.lastAuthorizationResponse.request.clientID }
    var isAuthorized: Bool { session.authState.isAuthorized }
    func refresh(completion: @escaping (AppleGoogleOAuthSession?, Error?) -> Void) {
        session.authState.performAction(freshTokens: { _, _, error in
            if let error = error {
                completion(nil, error)
                return
            }
            do {
                try self.store.save(authSession: self.session)
                completion(self, nil)
            } catch {
                completion(nil, error)
            }
        }, additionalRefreshParameters: session.authState.lastTokenResponse?.request.additionalParameters)
    }
}
