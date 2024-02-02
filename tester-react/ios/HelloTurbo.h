#import <React/RCTBridge.h>
#import <React/RCTBridgeModule.h>
#import <React/RCTBridge+Private.h>
#import <React/RCTUtils.h>
#import <ReactCommon/RCTTurboModule.h>
#import <jsi/jsi.h>
#import <batcave/batcave.h>
#include <ReactCommon/TurboModule.h>

using namespace facebook::jsi;
using namespace facebook::react;

/*
 RCTTurboModule
  anything objc should extend this
  write whatever methods call sql / whatever
  Creates ObjCTurboModule
 

 ObjCTurboModule
  Extends HostObject
  This will call functions from RCT turbo module
 */

struct HelloTurbo: react::ObjCTurboModule {
  HelloTurbo(const react::ObjCTurboModule::InitParams &params);
};

@interface HelloTurboIOS: NSObject<RCTTurboModule>
- (NSString *)helloWorld;
@end

@implementation HelloTurboIOS
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:(const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<HelloTurbo>(params);
}

- (NSString *) helloWorld {
  return @"Hello Shibasis";
}
@end


auto invokeHelloWorld = [] (jsi::Runtime &runtime, react::TurboModule &turboModule, const Value * args, size_t count) -> jsi::Value {
  return static_cast<react::ObjCTurboModule&>(turboModule).invokeObjCMethod(runtime, react::StringKind, "helloWorld", @selector(helloWorld), args, count);
};


HelloTurbo::HelloTurbo(const react::ObjCTurboModule::InitParams &params): react::ObjCTurboModule(params) {
  methodMap_["helloWorld"] = MethodMetadata { 0, invokeHelloWorld };
}
