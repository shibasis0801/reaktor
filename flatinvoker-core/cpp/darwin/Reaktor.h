#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
    int reaktorTest();
    const char* getName();
    char* getNameCpp();
    // Used by kotlin to send byte array to cpp
    int sendByteArray(const uint8_t* bytes, int size);
#ifdef __cplusplus
}
#endif

// ExampleClass.h
#import <Foundation/Foundation.h>

@interface ExampleClass : NSObject
- (NSString *)getHelloWorld;
@end

/**
 * Make global functions usable in C++
 */
#if defined(__cplusplus)
#define RCT_EXTERN extern "C" __attribute__((visibility("default")))
#define RCT_EXTERN_C_BEGIN extern "C" {
#define RCT_EXTERN_C_END }
#else
#define RCT_EXTERN extern __attribute__((visibility("default")))
#define RCT_EXTERN_C_BEGIN
#define RCT_EXTERN_C_END
#endif

/**
 * The RCT_DEBUG macro can be used to exclude error checking and logging code
 * from release builds to improve performance and reduce binary size.
 */
#ifndef RCT_DEBUG
#if DEBUG
#define RCT_DEBUG 1
#else
#define RCT_DEBUG 0
#endif
#endif

/**
 * The RCT_DEV macro can be used to enable or disable development tools
 * such as the debug executors, dev menu, red box, etc.
 */
#ifndef RCT_DEV
#if DEBUG
#define RCT_DEV 1
#else
#define RCT_DEV 0
#endif
#endif

/**
 * RCT_DEV_MENU can be used to toggle the dev menu separately from RCT_DEV.
 * By default though, it will inherit from RCT_DEV.
 */
#ifndef RCT_DEV_MENU
#define RCT_DEV_MENU RCT_DEV
#endif

#ifndef RCT_ENABLE_INSPECTOR
#if RCT_DEV && __has_include(<React/RCTInspectorDevServerHelper.h>)
#define RCT_ENABLE_INSPECTOR 1
#else
#define RCT_ENABLE_INSPECTOR 0
#endif
#endif

#ifndef ENABLE_PACKAGER_CONNECTION
#if RCT_DEV && (__has_include("RCTPackagerConnection.h") || __has_include(<React/RCTPackagerConnection.h>))
#define ENABLE_PACKAGER_CONNECTION 1
#else
#define ENABLE_PACKAGER_CONNECTION 0
#endif
#endif

#if RCT_DEV
#define RCT_IF_DEV(...) __VA_ARGS__
#else
#define RCT_IF_DEV(...)
#endif

#ifndef RCT_PROFILE
#define RCT_PROFILE RCT_DEV
#endif

/**
 * Add the default Metro packager port number
 */
#ifndef RCT_METRO_PORT
#define RCT_METRO_PORT 8081
#else
// test if RCT_METRO_PORT is empty
#define RCT_METRO_PORT_DO_EXPAND(VAL) VAL##1
#define RCT_METRO_PORT_EXPAND(VAL) RCT_METRO_PORT_DO_EXPAND(VAL)
#if !defined(RCT_METRO_PORT) || (RCT_METRO_PORT_EXPAND(RCT_METRO_PORT) == 1)
// Only here if RCT_METRO_PORT is not defined
// OR RCT_METRO_PORT is the empty string
#undef RCT_METRO_PORT
#define RCT_METRO_PORT 8081
#endif
#endif

/**
 * Add the default packager name
 */
#ifndef RCT_PACKAGER_NAME
#define RCT_PACKAGER_NAME @"Metro"
#endif

/**
 * By default, only raise an NSAssertion in debug mode
 * (custom assert functions will still be called).
 */
#ifndef RCT_NSASSERT
#define RCT_NSASSERT RCT_DEBUG
#endif

/**
 * Concat two literals. Supports macro expansions,
 * e.g. RCT_CONCAT(foo, __FILE__).
 */
#define RCT_CONCAT2(A, B) A##B
#define RCT_CONCAT(A, B) RCT_CONCAT2(A, B)

/**
 * This attribute is used for static analysis.
 */
#if !defined RCT_DYNAMIC
#if __has_attribute(objc_dynamic)
#define RCT_DYNAMIC __attribute__((objc_dynamic))
#else
#define RCT_DYNAMIC
#endif
#endif

/**
 * Throw an assertion for unimplemented methods.
 */
#define RCT_NOT_IMPLEMENTED(method)                                                                     \
  _Pragma("clang diagnostic push") _Pragma("clang diagnostic ignored \"-Wmissing-method-return-type\"") \
      _Pragma("clang diagnostic ignored \"-Wunused-parameter\"")                                        \
          RCT_EXTERN NSException *_RCTNotImplementedException(SEL, Class);                              \
  method NS_UNAVAILABLE                                                                                 \
  {                                                                                                     \
    @throw _RCTNotImplementedException(_cmd, [self class]);                                             \
  }                                                                                                     \
  _Pragma("clang diagnostic pop")

@interface RCTBridge
@end

@interface RCTCxxBridge: RCTBridge

@property (nonatomic, readonly) void *runtime;
- (instancetype)initWithParentBridge:(RCTBridge *)bridge NS_DESIGNATED_INITIALIZER;
@end

typedef NS_ENUM(NSUInteger, RCTFunctionType) {
RCTFunctionTypeNormal,
RCTFunctionTypePromise,
RCTFunctionTypeSync,
};

static inline const char *RCTFunctionDescriptorFromType(RCTFunctionType type)
{
    switch (type) {
        case RCTFunctionTypeNormal:
            return "async";
        case RCTFunctionTypePromise:
            return "promise";
        case RCTFunctionTypeSync:
            return "sync";
    }
};

@protocol RCTBridgeMethod <NSObject>

@property (nonatomic, readonly) const char *JSMethodName;
@property (nonatomic, readonly) RCTFunctionType functionType;

- (id)invokeWithBridge:(RCTBridge *)bridge module:(id)module arguments:(NSArray *)arguments;

@end

@class RCTCallableJSModules;

typedef struct RCTMethodInfo {
    const char *const jsName;
    const char *const objcName;
    const BOOL isSync;
} RCTMethodInfo;

void RCTRegisterModule(Class className);


/**
 * This constant can be returned from +methodQueue to force module
 * methods to be called on the JavaScript thread. This can have serious
 * implications for performance, so only use this if you're sure it's what
 * you need.
 *
 * NOTE: RCTJSThread is not a real libdispatch queue
 */
RCT_EXTERN dispatch_queue_t RCTJSThread;

/**
 * Provides the interface needed to register a bridge module.
 */
@protocol RCTBridgeModule <NSObject>


// Implemented by RCT_EXPORT_MODULE
+ (NSString *)moduleName;

@optional

/**
 * A reference to an RCTCallableJSModules. Useful for modules that need to
 * call into methods on JavaScript modules registered as callable with
 * React Native.
 *
 * To implement this in your module, just add `@synthesize callableJSModules =
 * _callableJSModules;`. If using Swift, add `@objc var callableJSModules:
 * RCTCallableJSModules!` to your module.
 */
@property (nonatomic, weak, readwrite) RCTCallableJSModules *callableJSModules;

/**
 * A reference to the RCTBridge. Useful for modules that require access
 * to bridge features, such as sending events or making JS calls. This
 * will be set automatically by the bridge when it initializes the module.
 * To implement this in your module, just add `@synthesize bridge = _bridge;`
 * If using Swift, add `@objc var bridge: RCTBridge!` to your module.
 */
@property (nonatomic, weak, readonly) RCTBridge *bridge;

/**
 * The queue that will be used to call all exported methods. If omitted, this
 * will call on a default background queue, which is avoids blocking the main
 * thread.
 *
 * If the methods in your module need to interact with UIKit methods, they will
 * probably need to call those on the main thread, as most of UIKit is main-
 * thread-only. You can tell React Native to call your module methods on the
 * main thread by returning a reference to the main queue, like this:
 *
 * - (dispatch_queue_t)methodQueue
 * {
 *   return dispatch_get_main_queue();
 * }
 *
 * If you don't want to specify the queue yourself, but you need to use it
 * inside your class (e.g. if you have internal methods that need to dispatch
 * onto that queue), you can just add `@synthesize methodQueue = _methodQueue;`
 * and the bridge will populate the methodQueue property for you automatically
 * when it initializes the module.
 */
@property (nonatomic, readonly) dispatch_queue_t methodQueue;


/**
 * Most modules can be used from any thread. All of the modules exported non-sync method will be called on its
 * methodQueue, and the module will be constructed lazily when its first invoked. Some modules have main need to access
 * information that's main queue only (e.g. most UIKit classes). Since we don't want to dispatch synchronously to the
 * main thread to this safely, we construct these modules and export their constants ahead-of-time.
 *
 * Note that when set to false, the module constructor will be called from any thread.
 *
 * This requirement is currently inferred by checking if the module has a custom initializer or if there's exported
 * constants. In the future, we'll stop automatically inferring this and instead only rely on this method.
 */
+ (BOOL)requiresMainQueueSetup;

/**
 * Injects methods into JS.  Entries in this array are used in addition to any
 * methods defined using the macros above.  This method is called only once,
 * before registration.
 */
- (NSArray<id<RCTBridgeMethod>> *)methodsToExport;

/**
 * Injects constants into JS. These constants are made accessible via NativeModules.ModuleName.X. It is only called once
 * for the lifetime of the bridge, so it is not suitable for returning dynamic values, but may be used for long-lived
 * values such as session keys, that are regenerated only as part of a reload of the entire React application.
 *
 * If you implement this method and do not implement `requiresMainQueueSetup`, you will trigger deprecated logic
 * that eagerly initializes your module on bridge startup. In the future, this behaviour will be changed to default
 * to initializing lazily, and even modules with constants will be initialized lazily.
 */
- (NSDictionary *)constantsToExport;

/**
 * Notifies the module that a batch of JS method invocations has just completed.
 */
- (void)batchDidComplete;

/**
 * Notifies the module that the active batch of JS method invocations has been
 * partially flushed.
 *
 * This occurs before -batchDidComplete, and more frequently.
 */
- (void)partialBatchDidFlush;

@end

/**
 * A class that allows NativeModules to call methods on JavaScript modules registered
 * as callable with React Native.
 */
@interface RCTCallableJSModules : NSObject

// Commented out to avoid adding more headers.
//- (void)setBridge:(RCTBridge *)bridge;
//- (void)setBridgelessJSModuleMethodInvoker:(RCTBridgelessJSModuleMethodInvoker)bridgelessJSModuleMethodInvoker;

- (void)invokeModule:(NSString *)moduleName method:(NSString *)methodName withArgs:(NSArray *)args;
- (void)invokeModule:(NSString *)moduleName
              method:(NSString *)methodName
            withArgs:(NSArray *)args
          onComplete:(dispatch_block_t)onComplete;
@end