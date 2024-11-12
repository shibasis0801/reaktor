#include <common/Engine.h>
#include <android/log.h>

void flowLogic() {
    auto &runtime = *Engine::runtime;
    auto Flow = runtime.global().getPropertyAsFunction(runtime, "Flow");
    auto flow = Flow.callAsConstructor(runtime);

    auto collectFn = flow.getObject(runtime).getPropertyAsFunction(runtime, "collect");

    auto observerFn = Engine::createFunction(
            "test",
            1,
            [](facebook::jsi::Runtime& rt, const facebook::jsi::Value& thisValue,
               const facebook::jsi::Value* arguments, size_t count) -> facebook::jsi::Value {

                const auto &value = arguments[0];
                __android_log_print(ANDROID_LOG_VERBOSE, "Shibasis Hermes", "%f", value.asNumber());

//                std::cout << value.asNumber() << std::endl;

                return {};
            }
    );
    collectFn.call(runtime, observerFn);

    auto emitFn = flow.getObject(runtime).getPropertyAsFunction(runtime, "emit");
    emitFn.call(runtime, 143);
}

static std::optional<std::string> readFile(const char *path) {
    auto absolutePath = std::filesystem::absolute(path);
    std::cout << "Reading " << absolutePath << "\n" ;
    std::ifstream fileStream(absolutePath);
    std::stringstream stringStream;

    if (fileStream) {
        stringStream << fileStream.rdbuf();
        fileStream.close();
    } else throw std::runtime_error("File not found");

    return stringStream.str();
}
unique_ptr<HermesRuntime> Engine::runtime = nullptr;
void Engine::start() {
    auto runtimeConfig =
            hermes::vm::RuntimeConfig::Builder().withIntl(false).build();
    runtime = facebook::hermes::makeHermesRuntime(runtimeConfig);
//    const auto path = "../../js/Flow.js";
//    auto code = readFile(path);
    std::optional<string> code = "var __BUNDLE_START_TIME__=this.nativePerformanceNow?nativePerformanceNow():Date.now(),__DEV__=false,process=this.process||{},__METRO_GLOBAL_PREFIX__='';process.env=process.env||{};process.env.NODE_ENV=process.env.NODE_ENV||\"production\";\n"
                "!(function(e){\"use strict\";e.__r=i,e[`${__METRO_GLOBAL_PREFIX__}__d`]=function(e,n,o){if(r.has(n))return;var i={dependencyMap:o,factory:e,hasError:!1,importedAll:t,importedDefault:t,isInitialized:!1,publicModule:{exports:{}}};r.set(n,i)},e.__c=o,e.__registerSegment=function(e,t,n){s[e]=t,n&&n.forEach((function(t){r.has(t)||v.has(t)||v.set(t,e)}))};var r=o(),t={},n={}.hasOwnProperty;function o(){return r=new Map}function i(e){var t=e,n=r.get(t);return n&&n.isInitialized?n.publicModule.exports:d(t,n)}function a(e){var n=e,o=r.get(n);if(o&&o.importedDefault!==t)return o.importedDefault;var a=i(n),l=a&&a.__esModule?a.default:a;return r.get(n).importedDefault=l}function l(e){var o=e,a=r.get(o);if(a&&a.importedAll!==t)return a.importedAll;var l,u=i(o);if(u&&u.__esModule)l=u;else{if(l={},u)for(var d in u)n.call(u,d)&&(l[d]=u[d]);l.default=u}return r.get(o).importedAll=l}i.importDefault=a,i.importAll=l,i.context=function(){throw new Error(\"The experimental Metro feature `require.context` is not enabled in your project.\")},i.resolveWeak=function(){throw new Error(\"require.resolveWeak cannot be called dynamically.\")};var u=!1;function d(r,t){if(!u&&e.ErrorUtils){var n;u=!0;try{n=h(r,t)}catch(r){e.ErrorUtils.reportFatalError(r)}return u=!1,n}return h(r,t)}var c=16,f=65535;function p(e){return{segmentId:e>>>c,localId:e&f}}i.unpackModuleId=p,i.packModuleId=function(e){return(e.segmentId<<c)+e.localId};var s=[],v=new Map;function h(t,n){if(!n&&s.length>0){var o,u=null!=(o=v.get(t))?o:0,d=s[u];null!=d&&(d(t),n=r.get(t),v.delete(t))}var c=e.nativeRequire;if(!n&&c){var f=p(t),h=f.segmentId;c(f.localId,h),n=r.get(t)}if(!n)throw Error('Requiring unknown module \"'+t+'\".');if(n.hasError)throw n.error;n.isInitialized=!0;var g=n,_=g.factory,m=g.dependencyMap;try{var w=n.publicModule;return w.id=t,_(e,i,a,l,w,w.exports,m),n.factory=void 0,n.dependencyMap=void 0,w.exports}catch(e){throw n.hasError=!0,n.error=e,n.isInitialized=!1,n.publicModule.exports=void 0,e}}})('undefined'!=typeof globalThis?globalThis:'undefined'!=typeof global?global:'undefined'!=typeof window?window:this);\n"
                "__d((function(g,r,i,a,m,e,d){Object.defineProperty(e,\"__esModule\",{value:!0}),Object.defineProperty(e,\"Flow\",{enumerable:!0,get:function(){return t.Flow}}),Object.defineProperty(e,\"StateFlow\",{enumerable:!0,get:function(){return t.StateFlow}});var t=r(d[0]),o=new t.StateFlow;o.collect(print),g.Flow=t.StateFlow,[1,2,3,4,5,6,7,8,9,10].forEach(o.emit)}),0,[1]);\n"
                "__d((function(g,r,i,a,m,e,d){var u=r(d[0]);Object.defineProperty(e,\"__esModule\",{value:!0}),e.default=e.StateFlow=void 0;var t=u(r(d[1])),n=u(r(d[2])),s=e.default=(0,t.default)((function u(){var t=this;(0,n.default)(this,u),this.values=[],this.empty=function(){return 0===t.size()},this.size=function(){return t.values.length},this.enqueue=function(u){t.values.push(u)},this.dequeue=function(){return t.values.shift()}}));e.StateFlow=(0,t.default)((function u(){var t=this;(0,n.default)(this,u),this.queue=new s,this.flushQueue=function(){for(;!t.queue.empty();)null==t.observer||t.observer(t.queue.dequeue())},this.collect=function(u){if(null!=t.observer)throw new Error(\"This version of flow does not support multiple observers, call the source function again\");return t.observer=u,t.flushQueue(),u},this.stopCollecting=function(u){t.observer=null},this.emit=function(u){null==t.observer?null!=t.queue&&t.queue.enqueue(u):t.observer(u)},this.stop=function(){t.observer=null}}))}),1,[2,3,7]);\n"
                "__d((function(g,r,i,a,m,_e,d){m.exports=function(e){return e&&e.__esModule?e:{default:e}},m.exports.__esModule=!0,m.exports.default=m.exports}),2,[]);\n"
                "__d((function(g,_r,i,a,m,_e,d){var e=_r(d[0]);function r(r,t){for(var o=0;o<t.length;o++){var n=t[o];n.enumerable=n.enumerable||!1,n.configurable=!0,\"value\"in n&&(n.writable=!0),Object.defineProperty(r,e(n.key),n)}}m.exports=function(e,t,o){return t&&r(e.prototype,t),o&&r(e,o),Object.defineProperty(e,\"prototype\",{writable:!1}),e},m.exports.__esModule=!0,m.exports.default=m.exports}),3,[4]);\n"
                "__d((function(g,r,_i,a,m,e,d){var t=r(d[0]).default,o=r(d[1]);m.exports=function(s){var n=o(s,\"string\");return\"symbol\"==t(n)?n:n+\"\"},m.exports.__esModule=!0,m.exports.default=m.exports}),4,[5,6]);\n"
                "__d((function(g,r,i,a,m,e,d){function o(t){return m.exports=o=\"function\"==typeof Symbol&&\"symbol\"==typeof Symbol.iterator?function(o){return typeof o}:function(o){return o&&\"function\"==typeof Symbol&&o.constructor===Symbol&&o!==Symbol.prototype?\"symbol\":typeof o},m.exports.__esModule=!0,m.exports.default=m.exports,o(t)}m.exports=o,m.exports.__esModule=!0,m.exports.default=m.exports}),5,[]);\n"
                "__d((function(g,_r,_i,a,m,_e,d){var r=_r(d[0]).default;m.exports=function(t,e){if(\"object\"!=r(t)||!t)return t;var i=t[Symbol.toPrimitive];if(void 0!==i){var o=i.call(t,e||\"default\");if(\"object\"!=r(o))return o;throw new TypeError(\"@@toPrimitive must return a primitive value.\")}return(\"string\"===e?String:Number)(t)},m.exports.__esModule=!0,m.exports.default=m.exports}),6,[5]);\n"
                "__d((function(g,r,i,_a,m,e,d){m.exports=function(o,n){if(!(o instanceof n))throw new TypeError(\"Cannot call a class as a function\")},m.exports.__esModule=!0,m.exports.default=m.exports}),7,[]);\n"
                "__r(0);";
    runtime->evaluateJavaScript(
            std::make_unique<facebook::jsi::StringBuffer>(*code), "main.js");
    flowLogic();
}


jsi::PropNameID Engine::createPropName(const std::string &propName) {
    return jsi::PropNameID::forAscii(*runtime, propName);
}

jsi::Function Engine::createFunction(
        const jsi::PropNameID &name,
        int argCount,
        jsi::HostFunctionType &&fn
) {
    return jsi::Function::createFromHostFunction(*runtime, name, argCount, std::move(fn));
}

jsi::Function Engine::createFunction(
        const std::string &name,
        int argCount,
        jsi::HostFunctionType &&fn
) {
    return jsi::Function::createFromHostFunction(*runtime, createPropName(name), argCount, std::move(fn));
}

// Java
jsi::Value Engine::createFromUTF8String(const std::string &contents) {
    return {
            *runtime,
            jsi::String::createFromUtf8(*runtime, contents)
    };
}

// C++
jsi::Value Engine::createFromAsciiString(const std::string &contents) {
    return {
            *runtime,
            jsi::String::createFromAscii(*runtime, contents)
    };
}


void Engine::createGlobalProperty(const std::string &name, jsi::Object object) {
    runtime->global().setProperty(*runtime, createPropName(name), object);
}

jsi::Object Engine::createFromHostObject(std::shared_ptr<jsi::HostObject> hostObject) {
    return jsi::Object::createFromHostObject(*runtime, std::move(hostObject));
}

jsi::Value Engine::getProperty(const std::string &name) {
    return runtime->global().getProperty(*runtime, name.c_str());
}

std::string Engine::getString(const jsi::Value &value) {
    return value.asString(*runtime).utf8(*runtime);
}

jsi::Function Engine::getFunction(const string &name) {
    return runtime->global().getPropertyAsFunction(*runtime, name.c_str());
}
