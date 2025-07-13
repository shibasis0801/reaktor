#pragma once
#import <Foundation/Foundation.h>
//#import <darwin/DarwinInvoker.h>

typedef void(^NSConsumer)(NSObject *value);
typedef void(^Resolver)(NSObject *value);
typedef void(^Rejecter)(NSError *error);
//
//namespace Reaktor {
//struct DarwinInvoker;
//struct DarwinPromise {
//    NSObject *kotlinPromise;
//    
//    explicit DarwinPromise(NSObject *kotlinPromise): kotlinPromise(kotlinPromise) {}
//    
//    void setResolver(Resolver resolver);
//    void setRejecter(Rejecter rejecter);
//};
//}
