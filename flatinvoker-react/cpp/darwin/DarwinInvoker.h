#import <darwin/DarwinBase.h>
#import <ReaktorHostObject.h>
#import <darwin/DarwinConverter.h>
//#import <types/Async.h>


namespace Reaktor {
    class DarwinInvoker: public PlatformInvoker {
        NSObject *instance;
        // A void* makes sure objc ARC and kotlin GC both don't delete the object.
        // some context: https://stackoverflow.com/questions/14854521/where-and-how-to-bridge

    public:
        explicit DarwinInvoker(NSObject *instance): instance(instance) {}
        jsi::Value operator()(
            const char *name,
            const jsi::Value *args,
            const FunctionDescriptor &descriptor
        ) override;
        
        void* invokeSelector(
            NSString *selectorName,
            NSArray *arguments,
            bool returnDefined = true
        );
        
        void* invokeSelector(
            std::string selectorName,
            NSArray *arguments,
            bool returnDefined = true
        );
        
        jsi::Value invokePromiseSelector(
            NSString *selectorName,
            NSArray *arguments
        );

        NSString* invokeStringSelector(
            NSString *selectorName,
            NSArray *arguments
        );
        
        NSNumber* invokeNumberSelector(
            NSString *selectorName,
            NSArray *arguments
        );
        
        NSArray*  invokeArraySelector(
            NSString *selectorName,
            NSArray *arguments
        );
        
        NSDictionary* invokeHashMapSelector(
            NSString *selectorName,
            NSArray *arguments
        );
    };
}

