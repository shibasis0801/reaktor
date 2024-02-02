#import "AppDelegate.h"
#import <React/RCTBridge.h>
#import <React/RCTBridgeModule.h>
#import <React/RCTBridge+Private.h>
#import <ReactCommon/RCTTurboModuleManager.h>

#import <React/RCTUtils.h>
#import <jsi/jsi.h>
#import <React/RCTBundleURLProvider.h>
#import <React/RCTRootView.h>
#import <batcave/batcave.h>
#import <darwin/DarwinInvoker.h>
#import <darwin/DarwinInstaller.h>
#import <BackgroundTasks/BackgroundTasks.h>
#import "HelloTurbo.h"
#import "batcaveTester-swift.h"

#ifdef FB_SONARKIT_ENABLED0
#import <FlipperKit/FlipperClient.h>
#import <FlipperKitLayoutPlugin/FlipperKitLayoutPlugin.h>
#import <FlipperKitUserDefaultsPlugin/FKUserDefaultsPlugin.h>
#import <FlipperKitNetworkPlugin/FlipperKitNetworkPlugin.h>
#import <SKIOSNetworkPlugin/SKIOSNetworkAdapter.h>
#import <FlipperKitReactPlugin/FlipperKitReactPlugin.h>

static void InitializeFlipper(UIApplication *application) {
  FlipperClient *client = [FlipperClient sharedClient];
  SKDescriptorMapper *layoutDescriptorMapper = [[SKDescriptorMapper alloc] initWithDefaults];
  [client addPlugin:[[FlipperKitLayoutPlugin alloc] initWithRootNode:application withDescriptorMapper:layoutDescriptorMapper]];
  [client addPlugin:[[FKUserDefaultsPlugin alloc] initWithSuiteName:nil]];
  [client addPlugin:[FlipperKitReactPlugin new]];
  [client addPlugin:[[FlipperKitNetworkPlugin alloc] initWithNetworkAdapter:[SKIOSNetworkAdapter new]]];
  [client start];
}
#endif

using namespace facebook;




@implementation AppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
#ifdef FB_SONARKIT_ENABLED0
    InitializeFlipper(application);
#endif

    RCTBridge *bridge = [[RCTBridge alloc] initWithDelegate:self launchOptions:launchOptions];
    RCTRootView *rootView = [[RCTRootView alloc] initWithBridge:bridge
                                                     moduleName:@"BatTester"
                                              initialProperties:nil];

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

using namespace facebook::jsi;
using namespace facebook::react;

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
        std::shared_ptr<CallInvoker> nativeCallInvoker = nullptr;// std::make_shared<MethodQueueNativeCallInvoker>(self.methodQueue);
        Reaktor::getLink().install(jsiRuntime, jsCallInvoker, nullptr);

        id dbModule = [BatcaveCommonDatabaseModule shared];
        Reaktor::addModule(dbModule, "LayoutDatabase");
    }
  
}


@end