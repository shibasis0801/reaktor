#import <darwin/DarwinInvoker.h>
#import <darwin/DarwinConverter.h>
#import <types/Async.h>

// Todo Configure VSCode to correctly see headers
namespace Reaktor {
    unordered_map<std::string, PromiseHandle> unresolvedPromises;

    /**
    Return a void* and do not store it anywhere.
    Instead cast the contents into some concrete variable
    */
    void* DarwinInvoker::invokeSelector(
        NSString *selectorName,
        NSArray *arguments,
        bool returnDefined
    ) {
        SEL selector = NSSelectorFromString(selectorName);
        if ([instance respondsToSelector:selector]) {
            NSMethodSignature *signature = [[instance class] instanceMethodSignatureForSelector:selector];
            NSInvocation *invocation = [NSInvocation invocationWithMethodSignature:signature];
            [invocation setSelector:selector];
        
            repeat(i, arguments.count) {
                id argument = arguments[i];
                [invocation setArgument:&argument atIndex:i + 2];
            }
            // Invoke the method (result stored inside invokation object)
            [invocation invokeWithTarget:instance];
            
            if (!returnDefined) {
                return nullptr;
            }

            void *result;
            // Get the stored result
            [invocation getReturnValue:&result];
            return result;
        }
        
        NSLog(@"No Such selector");
        return nullptr;
    }

    void* DarwinInvoker::invokeSelector(
        std::string selectorName,
        NSArray *arguments,
        bool returnDefined
    ) {
        NSString *selector = [NSString stringWithUTF8String:selectorName.c_str()];
        return invokeSelector(selector, arguments, returnDefined);
    }


    jsi::Value DarwinInvoker::invokePromiseSelector(
        NSString *selectorName,
        NSArray *arguments
    ) {
        unresolvedPromises["x"] = PromiseHandle();
        auto promise = newPromise(unresolvedPromises["x"]);
        
        auto num = make_shared<jsi::Value>(getRuntime(), 2);
        
        id kotlinPromise = (__bridge NSObject*) invokeSelector(selectorName, arguments);


        auto selector = Reaktor::getDarwinSelector("setNativeResolver", {{"resolver", Reaktor::DataType::Undefined}});

        auto promiseInvoker = DarwinInvoker(kotlinPromise);
        id resolver = ^(id data) {
            NSLog(@"invoker native data %@", data);
            
            auto value = make_shared<jsi::Value>(convertObjCObjectToJSIValue(getRuntime(), data));

            // use address as key
            unresolvedPromises["x"].resolve(value);
            // remove the handle
        };
        promiseInvoker.invokeSelector(selector, @[resolver], false);

        return promise;
    }



    NSString* DarwinInvoker::invokeStringSelector(
        NSString *selectorName,
        NSArray *arguments
    ) {
        void *result = invokeSelector(selectorName, arguments);
        return (__bridge NSString*)result;
    }

    NSNumber* DarwinInvoker::invokeNumberSelector(
        NSString *selectorName,
        NSArray *arguments
    ) {
        void *result = invokeSelector(selectorName, arguments);
        return (__bridge NSNumber*)result;
    }

    NSArray* DarwinInvoker::invokeArraySelector(
        NSString *selectorName,
        NSArray *arguments
    ) {
        void *result = invokeSelector(selectorName, arguments);
        return (__bridge NSArray*)result;
    }

    NSDictionary* DarwinInvoker::invokeHashMapSelector(
        NSString *selectorName, 
        NSArray *arguments
    ) {
        void *result = invokeSelector(selectorName, arguments);
        return (__bridge NSDictionary*)result;
    }

    jsi::Value DarwinInvoker::operator()(
        const char *name,
        const jsi::Value *args,
        const FunctionDescriptor &descriptor
    ) {
        auto [ _, iosSelector, argCount, returnType  ] = descriptor;
        NSMutableArray *darwinArgs = [NSMutableArray new];
        repeat(i, argCount) {
            id convertedDarwinArgs = convertJSIValueToObjCObject(getRuntime(), args[i], getLink().jsCallInvoker);
            [darwinArgs addObject:convertedDarwinArgs];
        }
        NSString *selectorName = [NSString stringWithUTF8String:iosSelector.c_str()];
        NSLog(@"Invoking selector %@", selectorName);

        switch (returnType) {
            case String:
            {
                NSString *resultString = invokeStringSelector(selectorName, [darwinArgs copy]);
                return convertNSStringToJSIString(getRuntime(), resultString);
                break;
            }
            case Number:
            {
                NSNumber *resultNumber = invokeNumberSelector(selectorName, [darwinArgs copy]);
                return convertNSNumberToJSINumber(getRuntime(), resultNumber);
                break;
            }
            case Array:
            {
                NSArray *resultArray = invokeArraySelector(selectorName, [darwinArgs copy]);
                return convertNSArrayToJSIArray(getRuntime(), resultArray);
                break;
            }
            case Object:
            {
                NSDictionary *resultObject = invokeHashMapSelector(selectorName, [darwinArgs copy]);
                return convertNSDictionaryToJSIObject(getRuntime(), resultObject);
                break;
            }
            case Promise:
            {
//                return invokePromiseSelector(selectorName, [darwinArgs copy]);
//                unresolvedPromises["x"] = PromiseHandle();
//                auto promise = newPromise(unresolvedPromises["x"]);
//                handle.resolve();
                return invokePromiseSelector(selectorName, [darwinArgs copy]);
            }
            default:
            {
                break;
            }
        }
        jsi::Value emptyValue;
        return emptyValue;
    }

}
