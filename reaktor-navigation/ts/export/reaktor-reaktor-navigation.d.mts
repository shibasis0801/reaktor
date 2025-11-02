type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare interface KtMutableMap<K, V> /* extends KtMap<K, V> */ {
    asJsMapView(): Map<K, V>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableMap": unique symbol;
    };
}
export declare abstract class KtMutableMap<K, V> extends KtSingleton<KtMutableMap.$metadata$.constructor>() {
    private constructor();
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace KtMutableMap.$metadata$ {
    abstract class constructor {
        fromJsMap<K, V>(map: ReadonlyMap<K, V>): KtMutableMap<K, V>;
        private constructor();
    }
}
export declare abstract class StatusCode {
    private constructor();
    static get CONTINUE(): StatusCode & {
        get name(): "CONTINUE";
        get ordinal(): 0;
    };
    static get SWITCHING_PROTOCOLS(): StatusCode & {
        get name(): "SWITCHING_PROTOCOLS";
        get ordinal(): 1;
    };
    static get PROCESSING(): StatusCode & {
        get name(): "PROCESSING";
        get ordinal(): 2;
    };
    static get OK(): StatusCode & {
        get name(): "OK";
        get ordinal(): 3;
    };
    static get CREATED(): StatusCode & {
        get name(): "CREATED";
        get ordinal(): 4;
    };
    static get ACCEPTED(): StatusCode & {
        get name(): "ACCEPTED";
        get ordinal(): 5;
    };
    static get NON_AUTHORITATIVE_INFORMATION(): StatusCode & {
        get name(): "NON_AUTHORITATIVE_INFORMATION";
        get ordinal(): 6;
    };
    static get NO_CONTENT(): StatusCode & {
        get name(): "NO_CONTENT";
        get ordinal(): 7;
    };
    static get RESET_CONTENT(): StatusCode & {
        get name(): "RESET_CONTENT";
        get ordinal(): 8;
    };
    static get PARTIAL_CONTENT(): StatusCode & {
        get name(): "PARTIAL_CONTENT";
        get ordinal(): 9;
    };
    static get MULTI_STATUS(): StatusCode & {
        get name(): "MULTI_STATUS";
        get ordinal(): 10;
    };
    static get ALREADY_REPORTED(): StatusCode & {
        get name(): "ALREADY_REPORTED";
        get ordinal(): 11;
    };
    static get IM_USED(): StatusCode & {
        get name(): "IM_USED";
        get ordinal(): 12;
    };
    static get MULTIPLE_CHOICES(): StatusCode & {
        get name(): "MULTIPLE_CHOICES";
        get ordinal(): 13;
    };
    static get MOVED_PERMANENTLY(): StatusCode & {
        get name(): "MOVED_PERMANENTLY";
        get ordinal(): 14;
    };
    static get FOUND(): StatusCode & {
        get name(): "FOUND";
        get ordinal(): 15;
    };
    static get SEE_OTHER(): StatusCode & {
        get name(): "SEE_OTHER";
        get ordinal(): 16;
    };
    static get NOT_MODIFIED(): StatusCode & {
        get name(): "NOT_MODIFIED";
        get ordinal(): 17;
    };
    static get USE_PROXY(): StatusCode & {
        get name(): "USE_PROXY";
        get ordinal(): 18;
    };
    static get TEMPORARY_REDIRECT(): StatusCode & {
        get name(): "TEMPORARY_REDIRECT";
        get ordinal(): 19;
    };
    static get PERMANENT_REDIRECT(): StatusCode & {
        get name(): "PERMANENT_REDIRECT";
        get ordinal(): 20;
    };
    static get BAD_REQUEST(): StatusCode & {
        get name(): "BAD_REQUEST";
        get ordinal(): 21;
    };
    static get UNAUTHORIZED(): StatusCode & {
        get name(): "UNAUTHORIZED";
        get ordinal(): 22;
    };
    static get PAYMENT_REQUIRED(): StatusCode & {
        get name(): "PAYMENT_REQUIRED";
        get ordinal(): 23;
    };
    static get FORBIDDEN(): StatusCode & {
        get name(): "FORBIDDEN";
        get ordinal(): 24;
    };
    static get NOT_FOUND(): StatusCode & {
        get name(): "NOT_FOUND";
        get ordinal(): 25;
    };
    static get METHOD_NOT_ALLOWED(): StatusCode & {
        get name(): "METHOD_NOT_ALLOWED";
        get ordinal(): 26;
    };
    static get NOT_ACCEPTABLE(): StatusCode & {
        get name(): "NOT_ACCEPTABLE";
        get ordinal(): 27;
    };
    static get PROXY_AUTHENTICATION_REQUIRED(): StatusCode & {
        get name(): "PROXY_AUTHENTICATION_REQUIRED";
        get ordinal(): 28;
    };
    static get REQUEST_TIMEOUT(): StatusCode & {
        get name(): "REQUEST_TIMEOUT";
        get ordinal(): 29;
    };
    static get CONFLICT(): StatusCode & {
        get name(): "CONFLICT";
        get ordinal(): 30;
    };
    static get GONE(): StatusCode & {
        get name(): "GONE";
        get ordinal(): 31;
    };
    static get LENGTH_REQUIRED(): StatusCode & {
        get name(): "LENGTH_REQUIRED";
        get ordinal(): 32;
    };
    static get PRECONDITION_FAILED(): StatusCode & {
        get name(): "PRECONDITION_FAILED";
        get ordinal(): 33;
    };
    static get PAYLOAD_TOO_LARGE(): StatusCode & {
        get name(): "PAYLOAD_TOO_LARGE";
        get ordinal(): 34;
    };
    static get URI_TOO_LONG(): StatusCode & {
        get name(): "URI_TOO_LONG";
        get ordinal(): 35;
    };
    static get UNSUPPORTED_MEDIA_TYPE(): StatusCode & {
        get name(): "UNSUPPORTED_MEDIA_TYPE";
        get ordinal(): 36;
    };
    static get RANGE_NOT_SATISFIABLE(): StatusCode & {
        get name(): "RANGE_NOT_SATISFIABLE";
        get ordinal(): 37;
    };
    static get EXPECTATION_FAILED(): StatusCode & {
        get name(): "EXPECTATION_FAILED";
        get ordinal(): 38;
    };
    static get IM_A_TEAPOT(): StatusCode & {
        get name(): "IM_A_TEAPOT";
        get ordinal(): 39;
    };
    static get MISDIRECTED_REQUEST(): StatusCode & {
        get name(): "MISDIRECTED_REQUEST";
        get ordinal(): 40;
    };
    static get UNPROCESSABLE_ENTITY(): StatusCode & {
        get name(): "UNPROCESSABLE_ENTITY";
        get ordinal(): 41;
    };
    static get LOCKED(): StatusCode & {
        get name(): "LOCKED";
        get ordinal(): 42;
    };
    static get FAILED_DEPENDENCY(): StatusCode & {
        get name(): "FAILED_DEPENDENCY";
        get ordinal(): 43;
    };
    static get TOO_EARLY(): StatusCode & {
        get name(): "TOO_EARLY";
        get ordinal(): 44;
    };
    static get UPGRADE_REQUIRED(): StatusCode & {
        get name(): "UPGRADE_REQUIRED";
        get ordinal(): 45;
    };
    static get PRECONDITION_REQUIRED(): StatusCode & {
        get name(): "PRECONDITION_REQUIRED";
        get ordinal(): 46;
    };
    static get TOO_MANY_REQUESTS(): StatusCode & {
        get name(): "TOO_MANY_REQUESTS";
        get ordinal(): 47;
    };
    static get REQUEST_HEADER_FIELDS_TOO_LARGE(): StatusCode & {
        get name(): "REQUEST_HEADER_FIELDS_TOO_LARGE";
        get ordinal(): 48;
    };
    static get UNAVAILABLE_FOR_LEGAL_REASONS(): StatusCode & {
        get name(): "UNAVAILABLE_FOR_LEGAL_REASONS";
        get ordinal(): 49;
    };
    static get INTERNAL_SERVER_ERROR(): StatusCode & {
        get name(): "INTERNAL_SERVER_ERROR";
        get ordinal(): 50;
    };
    static get NOT_IMPLEMENTED(): StatusCode & {
        get name(): "NOT_IMPLEMENTED";
        get ordinal(): 51;
    };
    static get BAD_GATEWAY(): StatusCode & {
        get name(): "BAD_GATEWAY";
        get ordinal(): 52;
    };
    static get SERVICE_UNAVAILABLE(): StatusCode & {
        get name(): "SERVICE_UNAVAILABLE";
        get ordinal(): 53;
    };
    static get GATEWAY_TIMEOUT(): StatusCode & {
        get name(): "GATEWAY_TIMEOUT";
        get ordinal(): 54;
    };
    static get HTTP_VERSION_NOT_SUPPORTED(): StatusCode & {
        get name(): "HTTP_VERSION_NOT_SUPPORTED";
        get ordinal(): 55;
    };
    static get VARIANT_ALSO_NEGOTIATES(): StatusCode & {
        get name(): "VARIANT_ALSO_NEGOTIATES";
        get ordinal(): 56;
    };
    static get INSUFFICIENT_STORAGE(): StatusCode & {
        get name(): "INSUFFICIENT_STORAGE";
        get ordinal(): 57;
    };
    static get LOOP_DETECTED(): StatusCode & {
        get name(): "LOOP_DETECTED";
        get ordinal(): 58;
    };
    static get NOT_EXTENDED(): StatusCode & {
        get name(): "NOT_EXTENDED";
        get ordinal(): 59;
    };
    static get NETWORK_AUTHENTICATION_REQUIRED(): StatusCode & {
        get name(): "NETWORK_AUTHENTICATION_REQUIRED";
        get ordinal(): 60;
    };
    get name(): "CONTINUE" | "SWITCHING_PROTOCOLS" | "PROCESSING" | "OK" | "CREATED" | "ACCEPTED" | "NON_AUTHORITATIVE_INFORMATION" | "NO_CONTENT" | "RESET_CONTENT" | "PARTIAL_CONTENT" | "MULTI_STATUS" | "ALREADY_REPORTED" | "IM_USED" | "MULTIPLE_CHOICES" | "MOVED_PERMANENTLY" | "FOUND" | "SEE_OTHER" | "NOT_MODIFIED" | "USE_PROXY" | "TEMPORARY_REDIRECT" | "PERMANENT_REDIRECT" | "BAD_REQUEST" | "UNAUTHORIZED" | "PAYMENT_REQUIRED" | "FORBIDDEN" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "NOT_ACCEPTABLE" | "PROXY_AUTHENTICATION_REQUIRED" | "REQUEST_TIMEOUT" | "CONFLICT" | "GONE" | "LENGTH_REQUIRED" | "PRECONDITION_FAILED" | "PAYLOAD_TOO_LARGE" | "URI_TOO_LONG" | "UNSUPPORTED_MEDIA_TYPE" | "RANGE_NOT_SATISFIABLE" | "EXPECTATION_FAILED" | "IM_A_TEAPOT" | "MISDIRECTED_REQUEST" | "UNPROCESSABLE_ENTITY" | "LOCKED" | "FAILED_DEPENDENCY" | "TOO_EARLY" | "UPGRADE_REQUIRED" | "PRECONDITION_REQUIRED" | "TOO_MANY_REQUESTS" | "REQUEST_HEADER_FIELDS_TOO_LARGE" | "UNAVAILABLE_FOR_LEGAL_REASONS" | "INTERNAL_SERVER_ERROR" | "NOT_IMPLEMENTED" | "BAD_GATEWAY" | "SERVICE_UNAVAILABLE" | "GATEWAY_TIMEOUT" | "HTTP_VERSION_NOT_SUPPORTED" | "VARIANT_ALSO_NEGOTIATES" | "INSUFFICIENT_STORAGE" | "LOOP_DETECTED" | "NOT_EXTENDED" | "NETWORK_AUTHENTICATION_REQUIRED";
    get ordinal(): 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 | 25 | 26 | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 | 35 | 36 | 37 | 38 | 39 | 40 | 41 | 42 | 43 | 44 | 45 | 46 | 47 | 48 | 49 | 50 | 51 | 52 | 53 | 54 | 55 | 56 | 57 | 58 | 59 | 60;
    get code(): number;
    static values(): Array<StatusCode>;
    static valueOf(value: string): StatusCode;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace StatusCode.$metadata$ {
    const constructor: abstract new () => StatusCode;
}
export declare namespace StatusCode {
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Companion.$metadata$ {
        abstract class constructor /* implements SerializerFactory */ {
            invoke(code: number): StatusCode;
            private constructor();
        }
    }
}
export declare abstract class JsResult<T> {
    protected constructor(status: string);
    get status(): string;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace JsResult.$metadata$ {
    const constructor: abstract new <T>() => JsResult<T>;
}
export declare class JsSuccessResult<T> extends JsResult.$metadata$.constructor<T> {
    constructor(value: T);
    get value(): T;
    copy(value?: T): JsSuccessResult<T>;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace JsSuccessResult.$metadata$ {
    const constructor: abstract new <T>() => JsSuccessResult<T>;
}
export declare class JsFailureResult<T> extends JsResult.$metadata$.constructor<T> {
    constructor(error: Error);
    get error(): Error;
    copy(error?: Error): JsFailureResult<T>;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace JsFailureResult.$metadata$ {
    const constructor: abstract new <T>() => JsFailureResult<T>;
}
export declare function getPatnaikUserAgent(): string;
export declare abstract class PromiseState {
    private constructor();
    static get Initial(): PromiseState & {
        get name(): "Initial";
        get ordinal(): 0;
    };
    static get Pending(): PromiseState & {
        get name(): "Pending";
        get ordinal(): 1;
    };
    static get Resolved(): PromiseState & {
        get name(): "Resolved";
        get ordinal(): 2;
    };
    static get Rejected(): PromiseState & {
        get name(): "Rejected";
        get ordinal(): 3;
    };
    get name(): "Initial" | "Pending" | "Resolved" | "Rejected";
    get ordinal(): 0 | 1 | 2 | 3;
    static values(): Array<PromiseState>;
    static valueOf(value: string): PromiseState;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace PromiseState.$metadata$ {
    const constructor: abstract new () => PromiseState;
}
export declare class PromiseResult<T> {
    constructor(state: PromiseState, data?: Nullable<T>, error?: Nullable<Error>);
    get state(): PromiseState;
    get data(): Nullable<T>;
    get error(): Nullable<Error>;
    copy(state?: PromiseState, data?: Nullable<T>, error?: Nullable<Error>): PromiseResult<T>;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace PromiseResult.$metadata$ {
    const constructor: abstract new <T>() => PromiseResult<T>;
}
export declare function usePromise<T>(dependencies: Array<Nullable<any>>, promiseFactory: () => Nullable<Promise<T>>): PromiseResult<T>;
/** @deprecated  */
export declare const initHook: { get(): any; };
export declare function pingAsync(x: number): Promise<number>;
export declare abstract class HttpMethod {
    private constructor();
    static get GET(): HttpMethod & {
        get name(): "GET";
        get ordinal(): 0;
    };
    static get POST(): HttpMethod & {
        get name(): "POST";
        get ordinal(): 1;
    };
    static get PUT(): HttpMethod & {
        get name(): "PUT";
        get ordinal(): 2;
    };
    static get DELETE(): HttpMethod & {
        get name(): "DELETE";
        get ordinal(): 3;
    };
    static get PATCH(): HttpMethod & {
        get name(): "PATCH";
        get ordinal(): 4;
    };
    static get OPTIONS(): HttpMethod & {
        get name(): "OPTIONS";
        get ordinal(): 5;
    };
    static get HEAD(): HttpMethod & {
        get name(): "HEAD";
        get ordinal(): 6;
    };
    get name(): "GET" | "POST" | "PUT" | "DELETE" | "PATCH" | "OPTIONS" | "HEAD";
    get ordinal(): 0 | 1 | 2 | 3 | 4 | 5 | 6;
    static values(): Array<HttpMethod>;
    static valueOf(value: string): HttpMethod;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace HttpMethod.$metadata$ {
    const constructor: abstract new () => HttpMethod;
}
export declare abstract class Environment {
    private constructor();
    static get STAGE(): Environment & {
        get name(): "STAGE";
        get ordinal(): 0;
    };
    static get PROD(): Environment & {
        get name(): "PROD";
        get ordinal(): 1;
    };
    get name(): "STAGE" | "PROD";
    get ordinal(): 0 | 1;
    static values(): Array<Environment>;
    static valueOf(value: string): Environment;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Environment.$metadata$ {
    const constructor: abstract new () => Environment;
}
export declare namespace Environment {
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Companion.$metadata$ {
        abstract class constructor {
            get Header(): string;
            invoke(value: string): Environment;
            private constructor();
        }
    }
}
export declare interface BaseRequest {
    readonly headers: KtMutableMap<string, string>;
    readonly queryParams: KtMutableMap<string, string>;
    readonly pathParams: KtMutableMap<string, string>;
    environment: Environment;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.io.service.BaseRequest": unique symbol;
    };
}
export declare abstract class BaseRequest extends KtSingleton<BaseRequest.$metadata$.constructor>() {
    private constructor();
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace BaseRequest.$metadata$ {
    abstract class constructor {
        invoke(headers?: KtMutableMap<string, string>, queryParams?: KtMutableMap<string, string>, pathParams?: KtMutableMap<string, string>): BaseRequest;
        private constructor();
    }
}
export declare interface BaseResponse {
    readonly headers: KtMutableMap<string, string>;
    statusCode: StatusCode;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.io.service.BaseResponse": unique symbol;
    };
}
export declare abstract class BaseResponse extends KtSingleton<BaseResponse.$metadata$.constructor>() {
    private constructor();
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace BaseResponse.$metadata$ {
    abstract class constructor {
        invoke(headers?: KtMutableMap<string, string>, statusCode?: StatusCode): BaseResponse;
        private constructor();
    }
}
export declare abstract class RequestHandler<Request extends BaseRequest, Response extends BaseResponse> {
    constructor(method: HttpMethod, route: string, requestSerializer: any/* KSerializer<Request> */, responseSerializer: any/* KSerializer<Response> */);
    get method(): HttpMethod;
    get route(): string;
    get requestSerializer(): any/* KSerializer<Request> */;
    get responseSerializer(): any/* KSerializer<Response> */;
    get routePattern(): any/* RoutePattern */;
    url(request: Request, extraPathParams: Array<any/* Pair<string, string> */>): string;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace RequestHandler.$metadata$ {
    const constructor: abstract new <Request extends BaseRequest, Response extends BaseResponse>() => RequestHandler<Request, Response>;
}
export declare namespace RequestHandler {
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Companion.$metadata$ {
        abstract class constructor {
            get serializersModule(): any/* SerializersModule */;
            private constructor();
        }
    }
}
export declare abstract class Service /* extends Adapter<void> */ {
    constructor(baseUrl?: string);
    get baseUrl(): string;
    get handlers(): any/* ArrayList<RequestHandler<UnknownType *, UnknownType *>> */;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Service.$metadata$ {
    const constructor: abstract new () => Service;
}
/** @deprecated  */
export declare const initHook: { get(): any; };
/** @deprecated  */
export declare const initHook: { get(): any; };
export declare class Edge<Contract extends any> /* implements Unique, Visitable */ {
    constructor(source: PortCapability, consumer: ConsumerPort<Contract>, destination: PortCapability, provider: ProviderPort<Contract>);
    get source(): PortCapability;
    get consumer(): ConsumerPort<Contract>;
    get destination(): PortCapability;
    get provider(): ProviderPort<Contract>;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Edge.$metadata$ {
    const constructor: abstract new <Contract extends any>() => Edge<Contract>;
}
export declare function connectPort(consumerPort: ConsumerPort<any>, providerPort: ProviderPort<any>): any/* Result<void> */;
export declare function connectNode(node1: PortCapability, node2: PortCapability): void;
export declare class Graph /* implements Unique, Visitable, LifecycleCapability, DependencyCapability, ConcurrencyCapability */ {
    constructor(parentGraph?: Nullable<Graph>, dispatcher?: any/* CoroutineDispatcher */, id?: any/* Uuid */, label?: string, dependency?: (p0: any/* ScopeDSL */) => void, builder?: (p0: Graph) => void);
    get dependency(): (p0: any/* ScopeDSL */) => void;
    get nodes(): KtMutableMap<any/* Uuid */, Node>/* LinkedHashMap<Uuid, Node> */;
    attach(node: Node): any/* Result<void> */;
    detach(node: Node): void;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Graph.$metadata$ {
    const constructor: abstract new () => Graph;
}
export declare abstract class Node implements PortCapability/*, Unique, Visitable, LifecycleCapability, ConcurrencyCapability */ {
    protected constructor(graph: Graph, dispatcher?: any/* CoroutineDispatcher */, id?: any/* Uuid */, label?: string);
    get graph(): Graph;
    get consumerPorts(): KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>;
    get providerPorts(): KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>;
    get portEvents(): any/* SharedFlow<PortEvent> */;
    emit(event: PortEvent): void;
    readonly __doNotUseOrImplementIt: PortCapability["__doNotUseOrImplementIt"];
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Node.$metadata$ {
    const constructor: abstract new () => Node;
}
export declare class GraphNode extends Node.$metadata$.constructor {
    constructor(childGraph: Graph, parent: Graph);
    get childGraph(): Graph;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace GraphNode.$metadata$ {
    const constructor: abstract new () => GraphNode;
}
export declare abstract class LogicNode extends Node.$metadata$.constructor {
    constructor(graph: Graph);
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace LogicNode.$metadata$ {
    const constructor: abstract new () => LogicNode;
}
export declare class Properties {
    constructor(routeParams?: KtMutableMap<string, string>/* HashMap<string, string> */);
    get routeParams(): KtMutableMap<string, string>/* HashMap<string, string> */;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Properties.$metadata$ {
    const constructor: abstract new () => Properties;
}
export declare namespace Properties {
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Companion.$metadata$ {
        abstract class constructor {
            private constructor();
        }
    }
}
export declare interface RouteBinding<P extends Properties> {
    props(): any/* StateFlow<P> */;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.navigation.graph.RouteBinding": unique symbol;
    };
}
export declare class RouteNode<Props extends Properties> extends Node.$metadata$.constructor implements RouteBinding<Props> {
    constructor(graph: Graph, pattern: any/* RoutePattern */, props: Props);
    get pattern(): any/* RoutePattern */;
    get routeBinding(): ProviderPort<RouteBinding<Props>>;
    get propFlow(): any/* MutableStateFlow<Props> */;
    props(): any/* MutableStateFlow<Props> */;
    readonly __doNotUseOrImplementIt: Node["__doNotUseOrImplementIt"] & RouteBinding<Props>["__doNotUseOrImplementIt"];
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace RouteNode.$metadata$ {
    const constructor: abstract new <Props extends Properties>() => RouteNode<Props>;
}
export declare function route<Props extends Properties>(_this_: Graph, pattern: string, initialProps: Props): RouteNode<Props>;
export declare function view<Props extends Properties, State>(_this_: Graph, fn: (p0: Graph) => Node/* ViewNode<Props, State> */): Node/* ViewNode<Props, State> */;
export declare interface View {
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.navigation.graph.View": unique symbol;
    };
}
export declare class Key {
    constructor(key: string);
    get key(): string;
    copy(key?: string): Key;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Key.$metadata$ {
    const constructor: abstract new () => Key;
}
export declare class Type {
    constructor(type: string, kClass?: Nullable<any>/* Nullable<KClass<UnknownType *>> */);
    get type(): string;
    get kClass(): Nullable<any>/* Nullable<KClass<UnknownType *>> */;
    copy(type?: string, kClass?: Nullable<any>/* Nullable<KClass<UnknownType *>> */): Type;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Type.$metadata$ {
    const constructor: abstract new () => Type;
}
export declare namespace Type {
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Companion.$metadata$ {
        abstract class constructor {
            get _sequence(): any/* AtomicInt */;
            private constructor();
        }
    }
}
export declare abstract class Port<Contract extends any> /* implements Visitable */ {
    protected constructor(owner: PortCapability, key: Key, type: Type);
    get owner(): PortCapability;
    get key(): Key;
    get type(): Type;
    abstract isConnected(): boolean;
    get node(): Node;
    protected static createWithStrings<Contract extends any>(owner: PortCapability, key: string, type: string): Port<Contract>;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace Port.$metadata$ {
    const constructor: abstract new <Contract extends any>() => Port<Contract>;
}
export declare class ConsumerPort<Contract extends any> extends Port.$metadata$.constructor<Contract> {
    constructor(owner: PortCapability, key: Key, type: Type, edge?: Nullable<Edge<Contract>>);
    get edge(): Nullable<Edge<Contract>>;
    set edge(value: Nullable<Edge<Contract>>);
    get contract(): Nullable<Contract>;
    isConnected(): boolean;
    invoke<R>(fn: (p0: Contract) => R): R;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace ConsumerPort.$metadata$ {
    const constructor: abstract new <Contract extends any>() => ConsumerPort<Contract>;
}
export declare class ProviderPort<Contract extends any> extends Port.$metadata$.constructor<Contract> {
    constructor(owner: PortCapability, key: Key, type: Type, impl: Contract, edges?: KtMutableMap<ConsumerPort<Contract>, Edge<Contract>>/* LinkedHashMap<ConsumerPort<Contract>, Edge<Contract>> */);
    get impl(): Contract;
    get edges(): KtMutableMap<ConsumerPort<Contract>, Edge<Contract>>/* LinkedHashMap<ConsumerPort<Contract>, Edge<Contract>> */;
    isConnected(): boolean;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace ProviderPort.$metadata$ {
    const constructor: abstract new <Contract extends any>() => ProviderPort<Contract>;
}
export declare abstract class PortEvent {
    protected constructor(port: Port<any /*UnknownType **/>);
    get port(): Port<any /*UnknownType **/>;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace PortEvent.$metadata$ {
    const constructor: abstract new () => PortEvent;
}
export declare namespace PortEvent {
    class Created extends PortEvent.$metadata$.constructor {
        constructor(port: Port<any /*UnknownType **/>);
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Created.$metadata$ {
        const constructor: abstract new () => Created;
    }
    class Connected extends PortEvent.$metadata$.constructor {
        constructor(port: Port<any /*UnknownType **/>, other: Port<any /*UnknownType **/>);
        get other(): Port<any /*UnknownType **/>;
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Connected.$metadata$ {
        const constructor: abstract new () => Connected;
    }
    class Disconnected extends PortEvent.$metadata$.constructor {
        constructor(port: Port<any /*UnknownType **/>, other: Port<any /*UnknownType **/>);
        get other(): Port<any /*UnknownType **/>;
    }
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace Disconnected.$metadata$ {
        const constructor: abstract new () => Disconnected;
    }
}
export declare interface PortCapability {
    readonly consumerPorts: KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>;
    readonly providerPorts: KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>;
    readonly portEvents: any/* SharedFlow<PortEvent> */;
    emit(event: PortEvent): void;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.navigation.graph.PortCapability": unique symbol;
    };
}
export declare class PortCapabilityImpl implements PortCapability/*, ConcurrencyCapability */ {
    constructor(context?: Nullable<any>/* Nullable<CoroutineContext> */, consumerPorts?: KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>, providerPorts?: KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>, portEvents?: any/* MutableSharedFlow<PortEvent> */);
    get consumerPorts(): KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>;
    get providerPorts(): KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>;
    get portEvents(): any/* MutableSharedFlow<PortEvent> */;
    emit(event: PortEvent): void;
    readonly __doNotUseOrImplementIt: PortCapability["__doNotUseOrImplementIt"];
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace PortCapabilityImpl.$metadata$ {
    const constructor: abstract new () => PortCapabilityImpl;
}
export declare function provider<Contract extends any>(_this_: PortCapability, key: Key, type: Type, impl: Contract): ProviderPort<Contract>;
export declare function getProvider<Contract extends any>(_this_: PortCapability, key: Key, type: Type): Nullable<ProviderPort<Contract>>;
export declare function consumer<Contract extends any>(_this_: PortCapability, key: Key, type: Type): ConsumerPort<Contract>;
export declare function getConsumer<Contract extends any>(_this_: PortCapability, key: Key, type: Type): Nullable<ConsumerPort<Contract>>;
export declare function sanusanu(): void;
export declare interface ReactView extends View {
    Content(props: PropsWithChildren): Nullable<ReactNode>;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.navigation.util.ReactView": unique symbol;
    } & View["__doNotUseOrImplementIt"];
}
export declare abstract class ReactViewNode<Props extends Properties, State> extends /* ViewNode<Props, State> */ Node.$metadata$.constructor implements ReactView {
    constructor(graph: Graph);
    useNodeState(): StateInstance<State>;
    abstract Content(props: PropsWithChildren): Nullable<ReactNode>;
    readonly __doNotUseOrImplementIt: Node["__doNotUseOrImplementIt"] & ReactView["__doNotUseOrImplementIt"];
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace ReactViewNode.$metadata$ {
    const constructor: abstract new <Props extends Properties, State>() => ReactViewNode<Props, State>;
}