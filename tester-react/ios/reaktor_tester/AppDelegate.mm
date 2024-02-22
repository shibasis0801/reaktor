#import "AppDelegate.h"

#import <React/RCTBridge.h>
#import <React/RCTBundleURLProvider.h>
#import <React/RCTRootView.h>

#import <React/RCTAppSetupUtils.h>


@implementation AppDelegate

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


/*

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


@interface Batcave : NSObject <RCTBridgeModule>;
@property (nonatomic, assign) BOOL setBridgeOnMainQueue;
@end

@implementation Batcave

@synthesize bridge = _bridge;
@synthesize methodQueue = _methodQueue;

RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup {
    return YES;
}

- (void)setBridge:(RCTBridge *)bridge {
    _bridge = bridge;
    _setBridgeOnMainQueue = RCTIsMainQueue();
    [self installLibrary];
}

- (void)installLibrary {
    RCTCxxBridge *cxxBridge = (RCTCxxBridge *)self.bridge;
    if (cxxBridge.runtime) {
        jsi::Runtime* jsiRuntime = (jsi::Runtime *)cxxBridge.runtime;
        jsi::Runtime &runtime = *jsiRuntime;
        auto jsCallInvoker = self.bridge.jsCallInvoker;
        std::shared_ptr<CallInvoker> nativeCallInvoker = nullptr;
      // std::make_shared<MethodQueueNativeCallInvoker>(self.methodQueue);
//        Reaktor::getLink().install(jsiRuntime, jsCallInvoker, nullptr);
//        Reaktor::addModule();
    }

}


@end
*/
