Requirements: (these must be exposed)
1. Navigation Graph
2. Back Stack
3. Deep Linking
4. Cross-Platform Views
5. Cross-Platform Screens

Responsibilities: (these should be internal)
1. 

Assumptions: (this must be internal)



gpt ->
1. Core Navigation System:
   Router: Define a core router that can handle navigation requests, manage the navigation stack, and transition between screens.
   Routes: Define routes for each screen or module. Each route should have a unique identifier.
   Navigation Stack: Maintain a stack of routes to handle back navigation and to query the current state of the navigation.
2. Platform-Specific Implementations:
   Android: Utilize Android's FragmentManager or Jetpack Navigation Component to manage screen transitions.
   iOS: Use UINavigationController or custom view controller transitions.
   Web: Leverage the browser's history API or libraries like React Router (if integrating with React) to manage web page navigation.
3. Deep Linking:
   URL Parsing: Implement a URL parser that can convert URLs into corresponding routes.
   Route Matching: Match the parsed URL to the corresponding route in your navigation system.
   Parameter Extraction: Extract any parameters from the URL and pass them to the target screen or module.
   Platform Integration:
   Android: Handle deep links using Android's Intent system.
   iOS: Handle deep links using UIApplicationDelegate methods.
   Web: Directly use the parsed URL from the browser.
4. Transition Animations:
   Define default transition animations for each platform.
   Allow custom animations to be defined for specific route transitions.
5. Middleware & Interceptors:
   Implement middleware or interceptors that can execute code before or after navigation events. This can be useful for logging, analytics, or conditional navigation (e.g., authentication checks).
6. State Restoration:
   Implement state restoration so that if an app is killed in the background and then relaunched, it can restore its previous navigation state.

 