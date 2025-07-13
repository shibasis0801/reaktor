import AuthenticationServices
import Foundation

class AppleSignInCoordinator: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {

    private var window: UIWindow

    init(window: UIWindow) {
        self.window = window
    }

    public func startSignIn() {
        let provider = ASAuthorizationAppleIDProvider()
        let request = provider.createRequest()
        request.requestedScopes = [.fullName, .email]

        let authorizationController = ASAuthorizationController(authorizationRequests: [request])
        authorizationController.delegate = self
        authorizationController.presentationContextProvider = self
        authorizationController.performRequests()
    }

    // MARK: - ASAuthorizationControllerDelegate

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            print("Error: Could not cast credential to ASAuthorizationAppleIDCredential.")
            return
        }

        print("Apple Sign In Successful")

        // Unique, stable user identifier
        let userId = credential.user
        print("User ID: \(userId)")

        // User's full name (only available on first login)
        if let fullName = credential.fullName {
            let givenName = fullName.givenName ?? ""
            let familyName = fullName.familyName ?? ""
            print("Full Name: \(givenName) \(familyName)")
        }

        // User's email (only available on first login)
        if let email = credential.email {
            print("Email: \(email)")
        }

        // JWT Identity Token
        guard let identityTokenData = credential.identityToken,
              let identityToken = String(data: identityTokenData, encoding: .utf8) else {
            print("Error: Missing or invalid identity token.")
            return
        }
        print("Identity Token (JWT): \(identityToken)")

        // Authorization Code
        guard let authorizationCodeData = credential.authorizationCode,
              let authorizationCode = String(data: authorizationCodeData, encoding: .utf8) else {
            print("Error: Missing or invalid authorization code.")
            return
        }
        print("Authorization Code: \(authorizationCode)")
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        // Handle error, e.g., user cancelled the login
        print("Apple Sign In Failed: \(error.localizedDescription)")
    }

    // MARK: - ASAuthorizationControllerPresentationContextProviding

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        return self.window
    }
}
