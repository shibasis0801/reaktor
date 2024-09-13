We use Hermes standalone to execute typescript.

We use metro because metro is optimised for mobile (RAM bundles, future features), future support for vite will come when I complete actual features.

You are welcome to raise a PR for adding support.

While many react native components are used, the React Native framework is not.

This is done for few reasons
1. Better design without brittleness from the changing RN API
2. Allow usage in Kotlin Multiplatform without adding a React Native dependency.

Why not Turbo Modules ?
Turbo Modules have limitations from backward compatibility and other things. This does not.


Why not CashApp/Zipline ?
This allows you to bundle things and use npm libraries as usual. CashApp requires Kotlin/JS or your own bundling setup.

Can this support Flutter ?
Yes, that is the plan once FlexBuffer based full functionality is written for RN and KMM. This is supposed to be framework agnostic, and Flutter should only need a DartBinaryTransport and DartCallInvoker to be implemented. 
