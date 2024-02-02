High level concerns in an app

1. UI
2. Logic
3. Platform
4. Testing
5. Deployment

## UI
User interactions
High performance visuals / animations
Unified design
Localisation

## Logic
Business logic

## Platform
Database
Network
Authentication
Analytics
Camera
Payments
Storage
Multithreading
Location
Platform Specific features


## Testing
Feature e2e testing
Performance testing
Security testing
Compliance testing


## Deployment 
Packaging
App Store / Play Store / Shuttle
Release process


## UI
React Native is a great fit due to all platform support and ecosystem already present

## Logic
This can be implemented in multiple ways. 
Currently we are doing in JS / Go which is fine.

## Platform
This is where KMM comes into play.
We can implement a lot of the platform layer in KMM.

## Testing 
We are focusing on improving our testing quality 
So that in future we can rely a lot more on automated tests.

## Deployment
This remains purely native. 
And we can focus on streamlining & automating the release process.


# Where does JSI help ?
We need a communication layer from UI to Platform (with / without kmm)
This used to be the bridge, an asynchronous message passing mechanism between two separate threads 
JS runs on one thread
Native runs on another
Both communicate through JSON messages


## What is JSI?
JS always runs on a C++ engine (v8, hermes, javascriptcore)
If we mount the JS engine directly on the main thread and if we can communicate with it synchronously
Then we can reduce the latency a lot. 

Mounting on main thread is simple, your JVM/iOS can load it
Communicating is tricky
All C++ JS engines can be manipulated using the C++ bindings (JNI / ObjC)
But this will bring engine specific code into the app. Not great. 

JSI is a way to standardise this communication. 
JavaScriptInterface is implemented by each JS engine.
And used by our app. 

Any JS code we write can now run on the main thread.
The JS event loop runs in a different thread by design.
But when the event loop picks a task to run. 
The task runs on the main thread.

JSI is only meant to be invoked from the main thread except for a way using CallInvoker. 

## Async bad, sync good ?
Not necessarily. But control is definitely good.
We can still do async work very well. 
How ?
Invokers. 
The event loop exposes an object called CallInvoker
It has a method invokeAsync
invokeAsync pushes a C++ work object (lambda) to the event loop queue
This work object contains code which you want to execute later. 


## Example: Background DB Request
1. JS calls C++
2. C++ 
   1. Creates a promise and returns it
   2. Calls the native code and waits for it
   3. When native code sends a response, resolves the already sent Promise object
3. JSI CallInvoker invokeAsync pushes this invokation to the event loop
4. Event loop picks the resolve invokation and performs resolve in JS
5. JS gets the value in then()


## Why KMM ?
As you see, JSI is a great communication layer
But it does not care about what you do with platform.
In JSI, C++ is god and it completely relies on C++'s capability to call anything else. 

Old RN App

React UI
Bridge Communication <- JSI solves this 
Android/iOS/Web code <- KMM solves this

A lot of the code we write independently for each platform is very similar in purpose.
There exist platform differences, and platform specific feature which we should not do in kmm.

But for the 80%, we can move them to KMM.

## Why ?
Constraints help us optimise further. 

Let's see an example
We want to write a location module. 
We create an contract in text/confluence.
Then each platform dev implements the contract in a best effort basis.
We use it from RN.

Now what if we want to add functionality.
We make changes in all 3.
Now what if we want to do something differently on one platform.
We make changes in the specific one. 

This process iteratively results in differences between the 3 platforms on the same module. 
As we see with mynaco. 

And if we want to make some changes, the friction is very high.

## How does KMM help ?

### Decrease Friction 
Using KMM we can have all of this in a structured manner. 
All common functionalities can have one interface
And platform specific things have their own interfaces
And at the code level we can easily check for differences.
Friction decreases, and platform differences are highlighted. 

### Optimise based on constraints
Since we know that the native modules have a common contract we can rely on.
We can develop tooling to simplify the process of calling it
Concretely, in the db module
I have used these constraints to create a framework for JSI-KMM called Reaktor

With Reaktor, you can easily write a new binding for a function. 
Instead of having 3 imperative implementations for calling and type conversion (JNI, ObjC, JS)
You have a declarative way.
We don't restrict ourselves to React supplied types anymore, and create our own types. 
Instead of having a ReactWritableNativeMap, we just use HashMaps on Java and NSDicts on iOS.

If your type is supported in Reaktor, any function with any combination of those types (return / parameters)
is now supported. 

There is nothing like this present, yet.

### Codegen
Once we have an efficient and flexible way of implementing this (Reaktor)
We can write our own transpiler which brings much richer platform access to react.
Again, this is not present right now. 
Nearest is NativeScript, but it is still not as flexible and will bring additional size requirements (another runtime)

### Converts existing app codebases to app shells (already for ui, now for platform)
This helps us optimise the shells
Focus on deployment and testing
We don't have to worry about having lots of moving parts (native libraries) and can focus purely on
Improving and automating
Build Speed
Testing 
Release Process

This is my vision for us based on my experience.
