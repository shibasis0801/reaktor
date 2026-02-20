# Reaktor Auth - Technical Documentation

## Overview

**Reaktor Auth** is a **Kotlin Multiplatform (KMP)** authentication module that provides OAuth 2.0 / OIDC-based social login (Google and Apple) across Android, iOS, Web, and JVM (server) platforms. It features:

- **Multi-platform support**: Android, iOS, Web (JS), and JVM server
- **Social providers**: Google Sign-In and Apple Sign-In
- **JWT-based authentication**: Client gets ID token from provider → Server verifies JWT → Issues session token
- **Role-Based Access Control (RBAC)**: Users, Roles, Permissions, Contexts, and Sessions
- **PostgreSQL backend**: Using Exposed ORM with full RBAC schema
- **Type-safe API contracts**: Shared between client and server via kotlinx.serialization

---

## Project Structure

```
reaktor-auth/
├── src/
│   ├── commonMain/          # Shared code across all platforms
│   │   ├── kotlin/
│   │   │   └── dev/shibasis/reaktor/auth/
│   │   │       ├── AuthAdapter.kt              # Abstract auth adapter
│   │   │       ├── AuthProvider.kt             # Abstract provider (Google/Apple)
│   │   │       ├── AuthProviderUser.kt         # User data classes (GoogleUser, AppleUser)
│   │   │       ├── RoleBasedAuth.kt           # RBAC entities (User, Role, Permission, etc.)
│   │   │       ├── api/
│   │   │       │   ├── AuthService.kt         # Auth API contracts (LoginRequest/Response)
│   │   │       │   └── UserService.kt         # User API (placeholder)
│   │   │       └── ui/
│   │   │           ├── LoginButtons.kt        # Composable UI for login buttons
│   │   │           ├── GoogleIcon.kt          # Google icon
│   │   │           └── AppleIcon.kt           # Apple icon
│   │   └── composeResources/
│   │       └── files/                         # SVG assets (google.svg, apple.svg)
│   │
│   ├── androidMain/         # Android-specific implementation
│   │   └── kotlin/
│   │       └── dev/shibasis/reaktor/auth/
│   │           ├── AndroidAuthAdapter.kt      # Android auth adapter (uses CredentialManager)
│   │           ├── AndroidGoogleLogin.kt      # Google Sign-In via Credential Manager API
│   │           └── AndroidAppleLogin.kt       # Apple Sign-In (TODO - needs web flow)
│   │
│   ├── iosMain/             # iOS-specific implementation
│   │   └── kotlin/
│   │       └── dev/shibasis/reaktor/auth/
│   │           ├── DarwinAuthAdapter.kt       # iOS auth adapter
│   │           ├── DarwinGoogleLogin.kt       # Google Sign-In via GoogleSignIn CocoaPod
│   │           ├── DarwinAppleLogin.kt        # Apple Sign-In via AuthenticationServices
│   │           └── AppleSignInCoordinator.swift # Swift coordinator for Apple Sign-In
│   │
│   ├── jsMain/              # Web-specific implementation (INCOMPLETE)
│   │   └── kotlin/
│   │       └── dev/shibasis/reaktor/auth/
│   │           ├── WebAuthAdapter.kt          # Web auth adapter (basic skeleton)
│   │           ├── WebGoogleLogin.kt          # Google Sign-In (TODO)
│   │           └── WebAppleLogin.kt           # Apple Sign-In (TODO)
│   │
│   └── jvmMain/             # Server-side implementation (Spring Boot)
│       └── kotlin/
│           └── dev/shibasis/reaktor/auth/
│               ├── api/
│               │   └── AuthServer.kt          # Auth server endpoints
│               ├── services/
│               │   └── LoginInteractor.kt     # Core login business logic
│               ├── jwt/
│               │   ├── Authentication.kt      # JWT verification logic
│               │   └── DefaultSecurityConfig.kt # Security config
│               ├── db/
│               │   └── Repositories.kt        # Database repositories (UserRepository, AppRepository)
│               ├── AuthTables.kt              # Exposed ORM table definitions
│               ├── AuthAdapter.jvm.kt         # JVM-specific adapter
│               └── framework/                 # Reusable DB framework
│                   ├── CrudRepository.kt      # Base repository
│                   ├── ExposedAdapter.kt      # Exposed DB adapter
│                   ├── Auditable.kt           # Auditable entities
│                   └── db/                    # DB utilities
│
├── build.gradle.kts         # Multiplatform build configuration
├── package.json             # NPM config for web
└── reaktor_auth.podspec     # CocoaPods spec for iOS
```

---

## Architecture

### High-Level Flow

```
┌─────────────────┐
│   Client App    │
│ (Android/iOS/   │
│     Web)        │
└────────┬────────┘
         │
         │ 1. User clicks "Sign in with Google/Apple"
         │
         ▼
┌─────────────────┐
│  AuthProvider   │ ◄─── Platform-specific (AndroidGoogleLogin, DarwinAppleLogin, WebGoogleLogin)
│  (Google/Apple) │
└────────┬────────┘
         │
         │ 2. OAuth flow → Get ID Token (JWT)
         │
         ▼
┌─────────────────┐
│  AuthAdapter    │ ◄─── Orchestrates login flow
└────────┬────────┘
         │
         │ 3. Send LoginRequest (idToken, appId, provider) to server
         │
         ▼
┌─────────────────┐
│  AuthService    │ ◄─── HTTP client (ktor)
│  (Client)       │
└────────┬────────┘
         │
         │ 4. POST /auth/sign-in
         │
         ▼
┌─────────────────────────────────────────┐
│           SERVER (JVM)                  │
│  ┌──────────────────────────────────┐  │
│  │  AuthServer (Spring Boot)        │  │
│  └────────────┬─────────────────────┘  │
│               │                         │
│               ▼                         │
│  ┌──────────────────────────────────┐  │
│  │  LoginInteractor                 │  │
│  │  - Verify JWT with JwtVerifier   │  │
│  │  - Lookup/Create User in DB      │  │
│  │  - Return LoginResponse.Success  │  │
│  └────────────┬─────────────────────┘  │
│               │                         │
│               ▼                         │
│  ┌──────────────────────────────────┐  │
│  │  PostgreSQL (RBAC Schema)        │  │
│  │  - Users, Apps, Roles, etc.      │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
         │
         │ 5. LoginResponse.Success(user, profile)
         │
         ▼
┌─────────────────┐
│   Client App    │ ◄─── Store user session, navigate to home
└─────────────────┘
```

---

## Key Components

### 1. **AuthAdapter** (Abstract, Common)

**Location**: `src/commonMain/kotlin/dev/shibasis/reaktor/auth/AuthAdapter.kt`

**Purpose**: Central orchestrator for authentication. Each platform has its own implementation.

**Key Methods**:
- `register(provider: UserProvider, authProvider: AuthProvider)`: Register Google/Apple providers
- `login(appId: String, environment: Environment, userProvider: UserProvider)`: Main login method
- `logout()`: Platform-specific logout

**Flow**:
1. Calls the registered `AuthProvider` for the given `UserProvider` (GOOGLE/APPLE)
2. Gets `AuthProviderUser` (contains idToken, email, name)
3. Sends `LoginRequest` to server via `AuthService`
4. Returns `LoginResponse` (Success with User + profile, or Failure)

**Platform Implementations**:
- **Android**: `AndroidAuthAdapter` (uses `CredentialManager`)
- **iOS**: `DarwinAuthAdapter` (no controller dependencies)
- **Web**: `WebAuthAdapter` (controller = Unit, minimal)

---

### 2. **AuthProvider** (Abstract, Common)

**Location**: `src/commonMain/kotlin/dev/shibasis/reaktor/auth/AuthProvider.kt`

**Purpose**: Platform-specific OAuth login handler.

**Subtypes**:
- `GoogleAuthProvider<Adapter>`
- `AppleAuthProvider<Adapter>`

**Key Methods**:
- `login()`: Trigger OAuth flow, return `AuthProviderUser`
- `getUser()`: Get cached/current user
- `logout()`: Clear session

**Platform Implementations**:

#### Android
- **GoogleAuthProvider**: `AndroidGoogleLogin`
  - Uses `androidx.credentials.CredentialManager`
  - `GetGoogleIdOption` with serverClientId (OAuth audience)
  - Returns `GoogleUser` with idToken

- **AppleAuthProvider**: `AndroidAppleLogin`
  - Status: `TODO("Not yet implemented")`
  - Apple Sign-In on Android requires web-based flow (not native)

#### iOS
- **GoogleAuthProvider**: `DarwinGoogleLogin`
  - Uses `GoogleSignIn` CocoaPod
  - `GIDSignIn.sharedInstance.signInWithPresentingViewController()`
  - Returns `GoogleUser` with idToken

- **AppleAuthProvider**: `DarwinAppleLogin`
  - Uses `AuthenticationServices.ASAuthorizationAppleIDProvider`
  - Native iOS Sign in with Apple
  - Returns `AppleUser` with idToken

#### Web
- **GoogleAuthProvider**: `WebGoogleLogin`
  - Status: `TODO("Not yet implemented")`
  - Should use Google Identity Services (GSI) JavaScript SDK
  - Flow: Load `accounts.google.com` script → render button → get credential

- **AppleAuthProvider**: `WebAppleLogin`
  - Status: `TODO("Not yet implemented")`
  - Should use AppleID JavaScript SDK
  - Flow: Load `appleid.auth.js` → AppleID.auth.signIn() → get id_token

---

### 3. **AuthProviderUser** (Data Classes, Common)

**Location**: `src/commonMain/kotlin/dev/shibasis/reaktor/auth/AuthProviderUser.kt`

**Purpose**: Standardized user data from OAuth providers.

```kotlin
interface AuthProviderUser {
    val idToken: String       // JWT token from provider
    val givenName: String?    // First name
    val familyName: String?   // Last name
    val emailId: String       // Email address
    fun json(): JsonElement   // Serialize to JSON
}

data class GoogleUser(
    override val idToken: String,
    override val givenName: String?,
    override val familyName: String?,
    override val emailId: String,
    val imageUrl: String
): AuthProviderUser

data class AppleUser(
    override val idToken: String,
    override val givenName: String?,
    override val familyName: String?,
    override val emailId: String
): AuthProviderUser
```

**Note**: Apple only sends `givenName` and `familyName` **once** on first login. Server must store it immediately.

---

### 4. **AuthService** (API Contract, Common)

**Location**: `src/commonMain/kotlin/dev/shibasis/reaktor/auth/api/AuthService.kt`

**Purpose**: Define client-server API contract.

#### LoginRequest
```kotlin
@Serializable
data class LoginRequest(
    val idToken: String,              // JWT from Google/Apple
    val appId: String,                // UUID of the app
    val provider: UserProvider,       // GOOGLE or APPLE
    val givenName: String? = null,    // Required for Apple (first login only)
    val familyName: String? = null,   // Required for Apple (first login only)
    val newUserProfile: JsonElement,  // Extra profile data (gender, location)
    override var environment: Environment  // STAGE, PROD, etc.
): Request()
```

#### LoginResponse
```kotlin
sealed class LoginResponse: Response() {
    data class Success(
        val user: User,           // User entity from DB
        val profile: JsonElement  // User.data (custom profile)
    ): LoginResponse()

    sealed class Failure: LoginResponse() {
        data object InvalidIdToken          // JWT verification failed
        data object InvalidAppId            // App not found in DB
        data object UnsupportedUserProvider // Provider not configured
        data object RequiresUserName        // Apple: name not provided
        data object RequiresUserProfile     // User needs onboarding
        data class AppLoginFailure(val userProvider: UserProvider)
        data class ServerError(val message: String)
    }
}
```

#### AuthServiceClient
```kotlin
class AuthServiceClient(baseUrl: String): AuthService(baseUrl) {
    override val login = PostHandler<LoginRequest, LoginResponse>("/auth/sign-in") {
        http.Post(route) { setBody(it) }.body()
    }
}
```

---

### 5. **AuthServer** (Server, JVM)

**Location**: `src/jvmMain/kotlin/dev/shibasis/reaktor/auth/api/AuthServer.kt`

**Purpose**: Spring Boot REST endpoint for `/auth/sign-in`.

```kotlin
@Component
class AuthServer(private val loginInteractor: LoginInteractor): AuthService() {
    override val login = PostHandler("/sign-in") {
        loginInteractor.login(it)
    }
}
```

---

### 6. **LoginInteractor** (Server, JVM)

**Location**: `src/jvmMain/kotlin/dev/shibasis/reaktor/auth/services/LoginInteractor.kt`

**Purpose**: Core server-side login business logic.

**Flow**:
1. **Validate App**: Lookup `appId` in `AppRepository`
2. **Verify JWT**: Use `JwtVerifier` to validate idToken against Google/Apple JWKS
3. **Lookup User**: Query `UserRepository` by `appId + socialId + provider`
4. **Create User** (if new):
   - Requires `givenName` and `familyName` (critical for Apple)
   - Insert into `Users` table with status `ONBOARDING`
5. **Return Success**: Send `User` entity + profile data

**Key Code**:
```kotlin
suspend fun login(request: LoginRequest): LoginResponse {
    val app = appRepository.findById(request.appId.uuid())
        ?: return LoginResponse.Failure.InvalidAppId

    val authenticated = jwtVerifier(request)
        ?: return LoginResponse.Failure.InvalidIdToken

    val user = userRepository.findByAppIdAndProvider(...)
        ?: createNewUser(...)

    return LoginResponse.Success(user, user.data)
}
```

---

### 7. **JwtVerifier** (Server, JVM)

**Location**: `src/jvmMain/kotlin/dev/shibasis/reaktor/auth/jwt/Authentication.kt`

**Purpose**: Verify JWT tokens from Google and Apple using JWKS.

**Configuration**:
```kotlin
sealed class UserAuthenticationProvider {
    data class Google(val audiences: List<String>): External(
        userProvider = UserProvider.GOOGLE,
        issuer = "https://accounts.google.com",
        jwksUrl = "https://www.googleapis.com/oauth2/v3/certs",
        audiences
    )

    data class Apple(val audiences: List<String>): External(
        userProvider = UserProvider.APPLE,
        issuer = "https://appleid.apple.com",
        jwksUrl = "https://appleid.apple.com/auth/keys",
        audiences
    )
}
```

**How It Works**:
1. Fetch JWKS (JSON Web Key Set) from provider's URL
2. Verify JWT signature using RS256 algorithm
3. Validate claims: `iss` (issuer), `aud` (audience), `sub` (subject), `email`
4. Return `AuthenticatedUser(subject, provider, email)`

**Implementation**:
```kotlin
suspend operator fun invoke(loginRequest: LoginRequest): Result<AuthenticatedUser> {
    val processor = getProcessor(userAuthenticationProvider)
    return runCatching {
        processor.process(loginRequest.idToken, null)
    }.map {
        AuthenticatedUser(it, userProvider)
    }
}
```

---

## Database Schema (PostgreSQL)

### RBAC Entities

#### **Apps**
```sql
CREATE TABLE app (
    id UUID PRIMARY KEY,
    name VARCHAR(100),
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **Users**
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(100),
    social_id TEXT,           -- Google/Apple subject ID
    app_id UUID REFERENCES app(id),
    provider VARCHAR(20),     -- 'GOOGLE' or 'APPLE'
    status VARCHAR(50),       -- 'ONBOARDING', 'ACTIVE', 'INACTIVE', 'BANNED'
    data JSONB,               -- Custom profile (gender, location, etc.)
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **Roles**
```sql
CREATE TABLE role (
    id UUID PRIMARY KEY,
    name VARCHAR(50),
    app_id UUID REFERENCES app(id),
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **Permissions**
```sql
CREATE TABLE permission (
    id UUID PRIMARY KEY,
    name VARCHAR(100),
    app_id UUID REFERENCES app(id),
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **Contexts**
```sql
CREATE TABLE context (
    id UUID PRIMARY KEY,
    name VARCHAR(100),
    app_id UUID REFERENCES app(id),
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **Sessions**
```sql
CREATE TABLE session (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    app_id UUID REFERENCES app(id),
    context_id UUID REFERENCES context(id),
    expires_at TIMESTAMPTZ,
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **RolePermissions** (Many-to-Many)
```sql
CREATE TABLE role_permissions (
    id UUID PRIMARY KEY,
    role_id UUID REFERENCES role(id),
    permission_id UUID REFERENCES permission(id),
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

#### **UserRoles** (Many-to-Many with Context)
```sql
CREATE TABLE user_role (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    role_id UUID REFERENCES role(id),
    context_id UUID REFERENCES context(id),  -- Scoped permissions
    data JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

---

## Platform Status

| Platform | Google Sign-In | Apple Sign-In | Status |
|----------|----------------|---------------|--------|
| **Android** | ✅ **WORKING** (CredentialManager API) | ❌ TODO (needs web flow) | **Tested & Production-Ready** |
| **iOS** | ✅ **WORKING** (GoogleSignIn CocoaPod) | ✅ **WORKING** (AuthenticationServices) | **Tested & Production-Ready** |
| **Web** | ❌ **TODO** | ❌ **TODO** | **Not Implemented** |
| **Server (JVM)** | ✅ **WORKING** (JWT verification via JWKS) | ✅ **WORKING** (JWT verification via JWKS) | **Production-Ready** |

---

## Web Implementation Guide

### Current State

The web implementation has **skeleton classes only**:

```kotlin
// WebAuthAdapter.kt
class WebAuthAdapter(authService: String): AuthAdapter<Unit>(Unit, AuthServiceClient(authService)) {
    override suspend fun logout(): Result<Unit> = succeed(Unit)

    fun registerGoogleLogin(audience: String) {
        register(UserProvider.GOOGLE, WebGoogleLogin(this, audience))
    }

    fun registerAppleLogin() {
        register(UserProvider.APPLE, WebAppleLogin(this))
    }
}

// WebGoogleLogin.kt
class WebGoogleLogin(webAuthAdapter: WebAuthAdapter, audience: String):
    GoogleAuthProvider<WebAuthAdapter>(webAuthAdapter) {
    override suspend fun getUser(): Result<GoogleUser> = TODO()
    override suspend fun login(): Result<GoogleUser> = TODO()
}

// WebAppleLogin.kt
class WebAppleLogin(webAuthAdapter: WebAuthAdapter):
    AppleAuthProvider<WebAuthAdapter>(webAuthAdapter) {
    override suspend fun login(): Result<AppleUser> = TODO()
    override suspend fun getUser(): Result<AppleUser> = TODO()
}
```

---

### What Needs To Be Implemented

#### 1. **Google Sign-In for Web**

**SDK**: Google Identity Services (GSI)
**Docs**: https://developers.google.com/identity/gsi/web

**Implementation Steps**:

1. **Load GSI Library**:
   ```kotlin
   // Add to HTML or dynamically load
   external fun loadGoogleScript()

   @JsModule("https://accounts.google.com/gsi/client")
   external val google: GoogleIdentity
   ```

2. **Initialize Google Identity**:
   ```kotlin
   external interface GoogleIdentity {
       val accounts: GoogleAccounts
   }

   external interface GoogleAccounts {
       val id: GoogleId
   }

   external interface GoogleId {
       fun initialize(config: GoogleIdConfiguration)
       fun renderButton(parent: Element, options: GoogleButtonOptions)
       fun prompt()
   }

   external interface GoogleIdConfiguration {
       var client_id: String
       var callback: (CredentialResponse) -> Unit
       var auto_select: Boolean?
       var cancel_on_tap_outside: Boolean?
   }

   external interface CredentialResponse {
       val credential: String  // This is the JWT idToken
       val select_by: String
   }
   ```

3. **Implement WebGoogleLogin**:
   ```kotlin
   class WebGoogleLogin(
       adapter: WebAuthAdapter,
       private val audience: String  // OAuth 2.0 Client ID
   ): GoogleAuthProvider<WebAuthAdapter>(adapter) {

       private var currentUser: GoogleUser? = null

       init {
           // Initialize Google Identity on page load
           google.accounts.id.initialize(object : GoogleIdConfiguration {
               override var client_id = audience
               override var callback = { response: CredentialResponse ->
                   handleCredentialResponse(response)
               }
           })
       }

       private fun handleCredentialResponse(response: CredentialResponse) {
           val idToken = response.credential
           // Decode JWT to get user info (or just store it)
           currentUser = decodeGoogleJwt(idToken)
       }

       override suspend fun login(): Result<GoogleUser> {
           return suspendCancellableCoroutine { cont ->
               google.accounts.id.prompt { notification ->
                   if (notification.isNotDisplayed()) {
                       cont.resume(Result.failure(Exception("Google prompt not shown")))
                   }
               }
               // Wait for callback to set currentUser
               // Then resume with currentUser
           }
       }

       override suspend fun getUser(): Result<GoogleUser> {
           return if (currentUser != null) {
               Result.success(currentUser!!)
           } else {
               Result.failure(Exception("No Google user logged in"))
           }
       }
   }
   ```

4. **JWT Decoding** (for client-side user info):
   ```kotlin
   fun decodeGoogleJwt(idToken: String): GoogleUser {
       val parts = idToken.split(".")
       val payload = JSON.parse<dynamic>(atob(parts[1]))
       return GoogleUser(
           idToken = idToken,
           givenName = payload.given_name as? String,
           familyName = payload.family_name as? String,
           emailId = payload.email as String,
           imageUrl = payload.picture as? String ?: ""
       )
   }
   ```

**Alternative**: Use Google's "One Tap" button rendering:
```kotlin
google.accounts.id.renderButton(
    document.getElementById("googleButtonDiv"),
    object : GoogleButtonOptions {
        override var theme = "outline"
        override var size = "large"
        override var text = "continue_with"
    }
)
```

---

#### 2. **Apple Sign-In for Web**

**SDK**: AppleID JavaScript SDK
**Docs**: https://developer.apple.com/documentation/sign_in_with_apple/sign_in_with_apple_js

**Implementation Steps**:

1. **Load AppleID Script**:
   ```html
   <script src="https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js"></script>
   ```

2. **Define External Interfaces**:
   ```kotlin
   external object AppleID {
       val auth: AppleAuth
   }

   external interface AppleAuth {
       fun init(config: AppleAuthConfig)
       fun signIn(): Promise<AppleAuthResponse>
   }

   external interface AppleAuthConfig {
       var clientId: String
       var scope: String
       var redirectURI: String
       var state: String?
       var usePopup: Boolean?
   }

   external interface AppleAuthResponse {
       val authorization: AppleAuthorization
       val user: AppleUserInfo?  // Only on first sign-in
   }

   external interface AppleAuthorization {
       val code: String
       val id_token: String  // JWT
   }

   external interface AppleUserInfo {
       val name: AppleName?
       val email: String
   }

   external interface AppleName {
       val firstName: String?
       val lastName: String?
   }
   ```

3. **Implement WebAppleLogin**:
   ```kotlin
   class WebAppleLogin(
       adapter: WebAuthAdapter,
       private val clientId: String,
       private val redirectUri: String
   ): AppleAuthProvider<WebAuthAdapter>(adapter) {

       private var currentUser: AppleUser? = null

       init {
           AppleID.auth.init(object : AppleAuthConfig {
               override var clientId = this@WebAppleLogin.clientId
               override var scope = "name email"
               override var redirectURI = this@WebAppleLogin.redirectUri
               override var usePopup = true
           })
       }

       override suspend fun login(): Result<AppleUser> {
           return suspendCancellableCoroutine { cont ->
               AppleID.auth.signIn().then { response ->
                   val idToken = response.authorization.id_token
                   val user = AppleUser(
                       idToken = idToken,
                       givenName = response.user?.name?.firstName,
                       familyName = response.user?.name?.lastName,
                       emailId = response.user?.email ?: decodeAppleJwt(idToken).email
                   )
                   currentUser = user
                   cont.resume(Result.success(user))
               }.catch { error ->
                   cont.resume(Result.failure(Exception(error.toString())))
               }
           }
       }

       override suspend fun getUser(): Result<AppleUser> {
           return if (currentUser != null) {
               Result.success(currentUser!!)
           } else {
               Result.failure(Exception("No Apple user logged in"))
           }
       }
   }
   ```

4. **Decode Apple JWT** (for email):
   ```kotlin
   fun decodeAppleJwt(idToken: String): dynamic {
       val parts = idToken.split(".")
       return JSON.parse(atob(parts[1]))
   }
   ```

---

### Integration with UI

**Compose Multiplatform Web**:

```kotlin
@Composable
fun LoginScreen(authAdapter: WebAuthAdapter) {
    val scope = rememberCoroutineScope()

    Theme.GoogleLoginButton(
        onClick = {
            scope.launch {
                val response = authAdapter.login(
                    appId = "your-app-uuid",
                    environment = Environment.STAGE,
                    userProvider = UserProvider.GOOGLE
                )
                when (response) {
                    is LoginResponse.Success -> {
                        // Navigate to home, store session
                        println("Logged in: ${response.user.name}")
                    }
                    is LoginResponse.Failure -> {
                        // Show error
                        println("Login failed: $response")
                    }
                }
            }
        }
    )

    Spacer(Modifier.height(16.dp))

    Theme.AppleLoginButton(
        onClick = {
            scope.launch {
                authAdapter.login(
                    appId = "your-app-uuid",
                    environment = Environment.STAGE,
                    userProvider = UserProvider.APPLE
                )
            }
        }
    )
}
```

---

## Dependencies

### Common
- `kotlinx-serialization`
- `ktor-client` (HTTP)
- `kermit` (Logging)
- `reaktor-ui`, `reaktor-graph`, `reaktor-io` (internal modules)

### Android
- `androidx.credentials:credentials:1.3.0`
- `androidx.credentials:credentials-play-services-auth:1.3.0`
- `com.google.android.libraries.identity.googleid:googleid:1.1.1`

### iOS
- CocoaPod: `GoogleSignIn:8.0.0`
- Native framework: `AuthenticationServices`

### Web (Needs to be added)
```json
{
  "dependencies": {
    "google-identity-services": "^1.0.0"  // Or load via CDN
  }
}
```

### JVM Server
- `spring-boot-starter-webflux`
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-security`
- `exposed-core`, `exposed-jdbc`, `exposed-json` (ORM)
- `postgresql` driver
- `nimbus-jose-jwt` (JWT verification)

---

## Testing Checklist

### Web Implementation
- [ ] Load Google Identity Services SDK
- [ ] Implement `WebGoogleLogin.login()` and `getUser()`
- [ ] Load Apple ID SDK
- [ ] Implement `WebAppleLogin.login()` and `getUser()`
- [ ] Test OAuth redirect flow
- [ ] Test JWT extraction and decoding
- [ ] Test server-side JWT verification
- [ ] Handle popup blockers and CORS issues
- [ ] Add localStorage for session persistence
- [ ] Test logout flow

### Server
- [x] JWT verification for Google (JWKS)
- [x] JWT verification for Apple (JWKS)
- [x] User creation on first login
- [x] Handle Apple's missing name issue (require in request)
- [ ] Session management (create JWT tokens for app)
- [ ] Refresh token flow
- [ ] Role-based authorization middleware

---

## Configuration

### Client Setup (per platform)

**Android**:
```kotlin
val authAdapter = AndroidAuthAdapter(
    activity = this,
    authService = "https://api.yourapp.com/auth"
)
authAdapter.registerGoogleLogin(audience = "YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com")
```

**iOS**:
```kotlin
val authAdapter = DarwinAuthAdapter(
    controller = viewController,
    authService = "https://api.yourapp.com/auth"
)
authAdapter.registerGoogleLogin()
authAdapter.registerAppleLogin()
```

**Web**:
```kotlin
val authAdapter = WebAuthAdapter(
    authService = "https://api.yourapp.com/auth"
)
authAdapter.registerGoogleLogin(audience = "YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com")
authAdapter.registerAppleLogin()
```

### Server Setup (Spring Boot)

```kotlin
@Configuration
class AuthConfig {
    @Bean
    fun jwtVerifier(): JwtVerifier {
        return JwtVerifier(
            listOf(
                UserAuthenticationProvider.Google(
                    audiences = listOf("YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com")
                ),
                UserAuthenticationProvider.Apple(
                    audiences = listOf("YOUR_APPLE_SERVICE_ID")
                )
            )
        )
    }
}
```

**Environment Variables**:
```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/yourdb
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=secret

# OAuth
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
APPLE_SERVICE_ID=com.yourcompany.yourapp
```

---

## API Endpoints

### POST /auth/sign-in

**Request**:
```json
{
  "idToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ...",
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "provider": "GOOGLE",
  "givenName": "John",
  "familyName": "Doe",
  "environment": "STAGE"
}
```

**Response (Success)**:
```json
{
  "statusCode": 200,
  "user": {
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "name": "John Doe",
    "socialId": "google-12345",
    "appId": "550e8400-e29b-41d4-a716-446655440000",
    "provider": "GOOGLE",
    "status": "ACTIVE",
    "data": {
      "gender": "Male",
      "location": "0101000020E6100000..."
    }
  },
  "profile": { /* same as user.data */ }
}
```

**Response (Failure)**:
```json
{
  "statusCode": 400,
  "type": "InvalidIdToken"  // or InvalidAppId, RequiresUserName, etc.
}
```

---

## Security Considerations

1. **JWT Verification**: Always verify JWT on server using JWKS (never trust client)
2. **HTTPS Only**: All auth endpoints must be HTTPS in production
3. **CORS**: Configure CORS for web clients
4. **State/Nonce**: Add CSRF protection for OAuth flows
5. **Token Storage**: Use secure storage (Keychain/Keystore on mobile, httpOnly cookies on web)
6. **Apple Name Handling**: Store `givenName` and `familyName` on first login (Apple only sends once)
7. **Rate Limiting**: Add rate limiting on `/auth/sign-in` endpoint
8. **Audience Validation**: Ensure JWT `aud` claim matches your OAuth client ID

---

## Common Issues & Solutions

### Issue: Apple doesn't send name on subsequent logins
**Solution**: Server must store `givenName` and `familyName` from the first login request. Return error `RequiresUserName` if missing.

### Issue: Google Sign-In fails on web with "popup blocked"
**Solution**: Use `usePopup: false` or trigger login from a user gesture (button click).

### Issue: JWT verification fails with "Invalid signature"
**Solution**: Check that `audience` (client_id) matches the one used to generate the token.

### Issue: CORS error on web
**Solution**: Add CORS headers on server:
```kotlin
@Configuration
class WebConfig: WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/auth/**")
            .allowedOrigins("https://yourapp.com")
            .allowedMethods("POST", "OPTIONS")
    }
}
```

---

## Next Steps for Web Implementation

1. **Add external declarations** for Google Identity Services and Apple ID JS APIs
2. **Implement `WebGoogleLogin.login()`**:
   - Load GSI SDK
   - Initialize with client_id
   - Handle credential callback
   - Extract idToken
3. **Implement `WebAppleLogin.login()`**:
   - Load AppleID SDK
   - Initialize with clientId and redirectURI
   - Handle sign-in promise
   - Extract id_token
4. **Test OAuth flows** in browser
5. **Add session storage** (localStorage or sessionStorage)
6. **Implement logout** (clear local tokens + call provider logout)
7. **Add error handling** for network failures, token expiry, etc.
8. **Update UI components** to work with web platform

---

## References

- **Google Identity Services**: https://developers.google.com/identity/gsi/web
- **Apple Sign In JS**: https://developer.apple.com/documentation/sign_in_with_apple/sign_in_with_apple_js
- **Kotlin/JS External Declarations**: https://kotlinlang.org/docs/js-interop.html
- **JWT Debugging**: https://jwt.io
- **Nimbus JOSE JWT**: https://connect2id.com/products/nimbus-jose-jwt

---

## Architecture Decisions

### Why Kotlin Multiplatform?
- **Code Reuse**: Auth logic, API contracts, and business models shared across platforms
- **Type Safety**: Compile-time checks for API requests/responses
- **Platform Integration**: Each platform uses native OAuth SDKs (CredentialManager, AuthenticationServices, JS SDKs)

### Why JWT + JWKS?
- **Stateless**: Server doesn't need to store OAuth tokens
- **Security**: JWT signature verified using provider's public keys (JWKS)
- **Standard**: OAuth 2.0 / OIDC industry standard

### Why PostgreSQL + Exposed ORM?
- **Relational RBAC**: Complex permissions model requires joins
- **JSONB**: Flexible `data` field for custom profile storage
- **Type Safety**: Exposed provides type-safe SQL DSL in Kotlin

### Why Spring Boot?
- **Production-Ready**: Built-in security, OAuth2 resource server, WebFlux
- **Ecosystem**: Easy integration with PostgreSQL, JWT libraries
- **Kotlin-Friendly**: Full Kotlin support with coroutines

---

## Glossary

- **OAuth 2.0**: Open standard for authorization
- **OIDC**: OpenID Connect, authentication layer on top of OAuth 2.0
- **JWT**: JSON Web Token, self-contained token with claims
- **JWKS**: JSON Web Key Set, public keys for verifying JWT signatures
- **idToken**: JWT issued by identity provider (Google/Apple) after authentication
- **Audience**: OAuth client ID, used to validate JWT was issued for your app
- **Subject**: Unique user ID from provider (Google/Apple)
- **RBAC**: Role-Based Access Control
- **Context**: Scope for role assignments (e.g., per-organization permissions)

---

## Contact & Support

For questions about this module, contact the Reaktor team or refer to:
- Main Reaktor repo
- Slack: #reaktor-auth
- Docs: https://docs.reaktor.dev/auth

---

**Document Version**: 1.0
**Last Updated**: 2026-02-08
**Author**: Claude (Technical Analysis)
