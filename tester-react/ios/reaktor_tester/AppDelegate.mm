#import "AppDelegate.h"

#import <React/RCTBridge.h>
#import <React/RCTBridge+Private.h>
#import <ReactCommon/RCTTurboModuleManager.h>
#import <React/RCTBundleURLProvider.h>
#import <React/RCTRootView.h>
#import <React/RCTUtils.h>
#import <ReactCommon/CallInvoker.h>
#import <jsi/jsi.h>
#import <React/RCTAppSetupUtils.h>

@interface MyBridgeMethod : NSObject <RCTBridgeMethod>
@end

@implementation MyBridgeMethod

@synthesize JSMethodName = _JSMethodName;
@synthesize functionType = _functionType;

- (instancetype)init {
    self = [super init];
    if (self) {
        _JSMethodName = "shibasis";
        _functionType = RCTFunctionTypeSync;
    }
    return self;
}

- (id)invokeWithBridge:(RCTBridge *)bridge module:(id)module arguments:(NSArray *)arguments {
    return @"Shibasis";
}

@end


@interface MyCustomModule : NSObject <RCTBridgeModule>
@end
@implementation MyCustomModule

+(NSString *)moduleName
{
  return @"MyCustomModule";
}
+(void)load
{
  RCTRegisterModule(self);
}
- (NSArray<id<RCTBridgeMethod>> *)methodsToExport {
  MyBridgeMethod *myMethodInstance = [[MyBridgeMethod alloc] init];
  return @[myMethodInstance];
}

RCT_EXPORT_METHOD(sayHello:(NSString *)name callback:(RCTResponseSenderBlock)callback) {
    NSString *greeting = [NSString stringWithFormat:@"Hello, %@", name];
    callback(@[greeting]);
}
@end

@implementation AppDelegate

- (NSArray<id<RCTBridgeModule>> *)extraModulesForBridge:(RCTBridge *)bridge {
  return @[ [MyCustomModule new] ];
  return nil;
}


- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
  RCTAppSetupPrepareApp(application);

  RCTBridge *bridge = [[RCTBridge alloc] initWithDelegate:self launchOptions:launchOptions];

  UIView *rootView = RCTAppSetupDefaultRootView(bridge, @"ReaktorTester", nil);

  if (@available(iOS 13.0, *)) {
    rootView.backgroundColor = [UIColor systemBackgroundColor];
  } else {
    rootView.backgroundColor = [UIColor whiteColor];
  }

  self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
  UIViewController *rootViewController = [UIViewController new];
  rootViewController.view = rootView;
  self.window.rootViewController = rootViewController;
  [self.window makeKeyAndVisible];
  return YES;
}

- (NSURL *)sourceURLForBridge:(RCTBridge *)bridge
{
#if DEBUG
  return [[RCTBundleURLProvider sharedSettings] jsBundleURLForBundleRoot:@"index"];
#else
  return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
#endif
}

@end


using facebook::react::CallInvoker;
using facebook::jsi::Runtime;

using namespace facebook::jsi;
using namespace facebook::react;

class MethodQueueNativeCallInvoker : public CallInvoker {
private:
    dispatch_queue_t methodQueue_;

public:
    MethodQueueNativeCallInvoker(dispatch_queue_t methodQueue) : methodQueue_(methodQueue) {}
    void invokeAsync(std::function<void()> &&work) override
    {
        if (methodQueue_ == RCTJSThread) {
            work();
            return;
        }

        __block auto retainedWork = std::move(work);
        dispatch_async(methodQueue_, ^{
            retainedWork();
        });
    }

    void invokeSync(std::function<void()> &&work) override
    {
        if (methodQueue_ == RCTJSThread) {
            work();
            return;
        }

        __block auto retainedWork = std::move(work);
        dispatch_sync(methodQueue_, ^{
            retainedWork();
        });
    }
};


@interface Reaktor : NSObject <RCTBridgeModule>;
@end

@implementation Reaktor

@synthesize bridge = _bridge;
@synthesize methodQueue = _methodQueue;

RCT_EXPORT_MODULE(Reaktor)

+ (BOOL)requiresMainQueueSetup {
    return YES;
}

- (void)setBridge:(RCTBridge *)bridge {
    _bridge = bridge;
}

//- (void)installLibrary {
//    RCTCxxBridge *cxxBridge = (RCTCxxBridge *)self.bridge;
//    if (cxxBridge.runtime) {
//        jsi::Runtime* jsiRuntime = (jsi::Runtime *)cxxBridge.runtime;
//        jsi::Runtime &runtime = *jsiRuntime;
//        auto jsCallInvoker = self.bridge.jsCallInvoker;
//        std::shared_ptr<CallInvoker> nativeCallInvoker = nullptr;
////        Reaktor::getLink().install(jsiRuntime, jsCallInvoker, nullptr);
////        Reaktor::addModule();
//    }


@end
