type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare interface KtList<E> /* extends Collection<E> */ {
    asJsReadonlyArrayView(): ReadonlyArray<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtList": unique symbol;
    };
}
export declare namespace KtList {
    function fromJsArray<E>(array: ReadonlyArray<E>): KtList<E>;
}
export declare interface KtMutableList<E> extends KtList<E>/*, MutableCollection<E> */ {
    asJsArrayView(): Array<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableList": unique symbol;
    } & KtList<E>["__doNotUseOrImplementIt"];
}
export declare namespace KtMutableList {
    function fromJsArray<E>(array: ReadonlyArray<E>): KtMutableList<E>;
}
export declare interface KtMutableMap<K, V> /* extends KtMap<K, V> */ {
    asJsMapView(): Map<K, V>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableMap": unique symbol;
    };
}
export declare namespace KtMutableMap {
    function fromJsMap<K, V>(map: ReadonlyMap<K, V>): KtMutableMap<K, V>;
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
export declare namespace StatusCode {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => StatusCode;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor /* implements SerializerFactory */ {
                invoke(code: number): StatusCode;
                private constructor();
            }
        }
    }
}
export declare abstract class JsResult<T> {
    protected constructor(status: string);
    get status(): string;
}
export declare namespace JsResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <T>() => JsResult<T>;
    }
}
export declare class JsSuccessResult<T> extends JsResult.$metadata$.constructor<T> {
    constructor(value: T);
    get value(): T;
    copy(value?: T): JsSuccessResult<T>;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace JsSuccessResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <T>() => JsSuccessResult<T>;
    }
}
export declare class JsFailureResult<T> extends JsResult.$metadata$.constructor<T> {
    constructor(error: Error);
    get error(): Error;
    copy(error?: Error): JsFailureResult<T>;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace JsFailureResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <T>() => JsFailureResult<T>;
    }
}
export declare function getPatnaikUserAgent(): string;
export declare interface Unique {
    readonly id: any/* Uuid */;
    readonly label: string;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.portgraph.Unique": unique symbol;
    };
}
export declare class UniqueImpl implements Unique {
    constructor(id?: any/* Uuid */, label?: string);
    get id(): any/* Uuid */;
    get label(): string;
    readonly __doNotUseOrImplementIt: Unique["__doNotUseOrImplementIt"];
}
export declare namespace UniqueImpl {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UniqueImpl;
    }
}
export declare class Edge<Contract extends any> implements Unique, Visitable {
    constructor(source: PortCapability, consumer: ConsumerPort<Contract>, destination: PortCapability, provider: ProviderPort<Contract>);
    get source(): PortCapability;
    get consumer(): ConsumerPort<Contract>;
    get destination(): PortCapability;
    get provider(): ProviderPort<Contract>;
    invoke<R>(fn: (p0: Contract) => R): R;
    suspended<R>(fn: any /*Suspend functions are not supported*/): Promise<R>;
    toString(): string;
    get id(): any/* Uuid */;
    get label(): string;
    readonly __doNotUseOrImplementIt: Unique["__doNotUseOrImplementIt"] & Visitable["__doNotUseOrImplementIt"];
}
export declare namespace Edge {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Contract extends any>() => Edge<Contract>;
    }
}
export declare class PortGraph<Self extends PortGraph<Self, N>, N extends PortNode<Self>> implements Unique, Visitable {
    constructor(id?: any/* Uuid */, label?: string);
    get id(): any/* Uuid */;
    get label(): string;
    get nodes(): any/* Collection<N> */;
    attach(node: N): boolean;
    detach(node: N): boolean;
    close(): void;
    node(id: any/* Uuid */): Nullable<N>;
    toString(): string;
    readonly __doNotUseOrImplementIt: Unique["__doNotUseOrImplementIt"] & Visitable["__doNotUseOrImplementIt"];
}
export declare namespace PortGraph {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Self extends PortGraph<Self, N>, N extends PortNode<Self>>() => PortGraph<Self, N>;
    }
}
export declare function connectPort(consumerPort: ConsumerPort<any>, providerPort: ProviderPort<any>): any/* Result<Edge<any>> */;
export declare function connectNode(node1: PortCapability, node2: PortCapability): void;
export declare class PortNode<G extends PortGraph<any /*UnknownType **/, any /*UnknownType **/>> implements Unique, Visitable, PortCapability {
    constructor(graph: G, id?: any/* Uuid */, label?: string, portCapability?: PortCapability);
    get graph(): G;
    get id(): any/* Uuid */;
    get label(): string;
    close(): void;
    toString(): string;
    get consumerPorts(): KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>;
    get providerPorts(): KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>;
    addPortEventListener(listener: (p0: PortEvent) => void): void;
    removePortEventListener(listener: (p0: PortEvent) => void): void;
    emit(event: PortEvent): void;
    registerProvider<Functionality extends any>(keyType: KeyType, impl: Functionality): ProviderPort<Functionality>;
    getProvider<Functionality extends any>(keyType: KeyType): Nullable<ProviderPort<Functionality>>;
    registerConsumer<Functionality extends any>(keyType: KeyType): ConsumerPort<Functionality>;
    getConsumer<Functionality extends any>(keyType: KeyType): Nullable<ConsumerPort<Functionality>>;
    readonly __doNotUseOrImplementIt: Unique["__doNotUseOrImplementIt"] & Visitable["__doNotUseOrImplementIt"] & PortCapability["__doNotUseOrImplementIt"];
}
export declare namespace PortNode {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <G extends PortGraph<any /*UnknownType **/, any /*UnknownType **/>>() => PortNode<G>;
    }
}
export declare class ConsumerPort<Functionality extends any> extends Port.$metadata$.constructor<Functionality> /* implements AutoCloseable */ {
    constructor(owner: PortCapability, key: Key, type: Type);
    get edge(): Nullable<Edge<Functionality>>;
    set edge(value: Nullable<Edge<Functionality>>);
    get impl(): Nullable<Functionality>;
    isConnected(): boolean;
    __guard(): void;
    invoke<R>(fn: (p0: Functionality) => R): R;
    suspended<R>(fn: any /*Suspend functions are not supported*/): Promise<R>;
    toString(): string;
}
export declare namespace ConsumerPort {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Functionality extends any>() => ConsumerPort<Functionality>;
    }
}
export declare abstract class Port<Functionality extends any> implements Visitable {
    protected constructor(owner: PortCapability, key: Key, type: Type);
    get owner(): PortCapability;
    get key(): Key;
    get type(): Type;
    abstract isConnected(): boolean;
    protected static createWithStrings<Functionality extends any>(owner: PortCapability, key: string, type: string): Port<Functionality>;
    toString(): string;
    get qualifier(): string;
    readonly __doNotUseOrImplementIt: Visitable["__doNotUseOrImplementIt"];
}
export declare namespace Port {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Functionality extends any>() => Port<Functionality>;
    }
}
export declare class Key {
    constructor(key: string);
    get key(): string;
    copy(key?: string): Key;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace Key {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Key;
    }
}
export declare class Type {
    constructor(type: string, kClass?: Nullable<any>/* Nullable<KClass<UnknownType *>> */);
    get type(): string;
    get kClass(): Nullable<any>/* Nullable<KClass<UnknownType *>> */;
    toString(): string;
    copy(type?: string, kClass?: Nullable<any>/* Nullable<KClass<UnknownType *>> */): Type;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace Type {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Type;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                create(kClass: any/* KClass<UnknownType *> */): Type;
                private constructor();
            }
        }
    }
}
export declare class KeyType {
    constructor(key: Key, type: Type);
    get key(): Key;
    get type(): Type;
    copy(key?: Key, type?: Type): KeyType;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
    static invoke(key: string, type: string): KeyType;
}
export declare namespace KeyType {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KeyType;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                private constructor();
            }
        }
    }
}
export declare abstract class PortEvent {
    protected constructor(port: Port<any /*UnknownType **/>);
    get port(): Port<any /*UnknownType **/>;
}
export declare namespace PortEvent {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PortEvent;
    }
    class Created extends PortEvent.$metadata$.constructor {
        constructor(port: Port<any /*UnknownType **/>);
    }
    namespace Created {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Created;
        }
    }
    class Connected extends PortEvent.$metadata$.constructor {
        constructor(port: Port<any /*UnknownType **/>, other: Port<any /*UnknownType **/>);
        get other(): Port<any /*UnknownType **/>;
    }
    namespace Connected {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Connected;
        }
    }
    class Disconnected extends PortEvent.$metadata$.constructor {
        constructor(port: Port<any /*UnknownType **/>, other: Port<any /*UnknownType **/>);
        get other(): Port<any /*UnknownType **/>;
    }
    namespace Disconnected {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Disconnected;
        }
    }
}
export declare interface PortCapability {
    readonly consumerPorts: KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>;
    readonly providerPorts: KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>;
    addPortEventListener(listener: (p0: PortEvent) => void): void;
    removePortEventListener(listener: (p0: PortEvent) => void): void;
    emit(event: PortEvent): void;
    registerProvider<Functionality extends any>(keyType: KeyType, impl: Functionality): ProviderPort<Functionality>;
    getProvider<Functionality extends any>(keyType: KeyType): Nullable<ProviderPort<Functionality>>;
    registerConsumer<Functionality extends any>(keyType: KeyType): ConsumerPort<Functionality>;
    getConsumer<Functionality extends any>(keyType: KeyType): Nullable<ConsumerPort<Functionality>>;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.portgraph.port.PortCapability": unique symbol;
    };
}
export declare class PortCapabilityImpl implements PortCapability {
    constructor(consumerPorts?: KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>, providerPorts?: KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>, listeners?: KtMutableList<(p0: PortEvent) => void>);
    get consumerPorts(): KtMutableMap<Type, KtMutableMap<Key, ConsumerPort<any>>>;
    get providerPorts(): KtMutableMap<Type, KtMutableMap<Key, ProviderPort<any>>>;
    addPortEventListener(listener: (p0: PortEvent) => void): void;
    removePortEventListener(listener: (p0: PortEvent) => void): void;
    emit(event: PortEvent): void;
    registerProvider<Functionality extends any>(keyType: KeyType, impl: Functionality): ProviderPort<Functionality>;
    getProvider<Functionality extends any>(keyType: KeyType): Nullable<ProviderPort<Functionality>>;
    registerConsumer<Functionality extends any>(keyType: KeyType): ConsumerPort<Functionality>;
    getConsumer<Functionality extends any>(keyType: KeyType): Nullable<ConsumerPort<Functionality>>;
    readonly __doNotUseOrImplementIt: PortCapability["__doNotUseOrImplementIt"];
}
export declare namespace PortCapabilityImpl {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PortCapabilityImpl;
    }
}
export declare class ProviderPort<Functionality extends any> extends Port.$metadata$.constructor<Functionality> /* implements AutoCloseable */ {
    constructor(owner: PortCapability, key: Key, type: Type, impl: Functionality, edges?: KtMutableMap<ConsumerPort<Functionality>, Edge<Functionality>>/* LinkedHashMap<ConsumerPort<Functionality>, Edge<Functionality>> */);
    get impl(): Functionality;
    get edges(): KtMutableMap<ConsumerPort<Functionality>, Edge<Functionality>>/* LinkedHashMap<ConsumerPort<Functionality>, Edge<Functionality>> */;
    static create<Functionality extends any>(owner: PortCapability, key: string, impl: Functionality): ProviderPort<Functionality>;
    isConnected(): boolean;
    invoke<R>(fn: (p0: Functionality) => R): R;
    suspended<R>(fn: any /*Suspend functions are not supported*/): Promise<R>;
    toString(): string;
}
export declare namespace ProviderPort {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Functionality extends any>() => ProviderPort<Functionality>;
    }
}
export declare interface Visitable {
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.portgraph.visitor.Visitable": unique symbol;
    };
}
export declare interface Selector {
    neighbors(visitable: Visitable): KtList<Visitable>;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.portgraph.visitor.Selector": unique symbol;
    };
}
export declare abstract class StructuralSelector {
    static readonly getInstance: () => typeof StructuralSelector.$metadata$.type;
    private constructor();
}
export declare namespace StructuralSelector {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements Selector {
            neighbors(visitable: Visitable): KtList<Visitable>;
            readonly __doNotUseOrImplementIt: Selector["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare abstract class ConnectivitySelector {
    static readonly getInstance: () => typeof ConnectivitySelector.$metadata$.type;
    private constructor();
}
export declare namespace ConnectivitySelector {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements Selector {
            neighbors(visitable: Visitable): KtList<Visitable>;
            readonly __doNotUseOrImplementIt: Selector["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare interface Traverser {
    traverse(start: Visitable, selector: Selector, visitor: PortGraphVisitor): void;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.portgraph.visitor.Traverser": unique symbol;
    };
}
export declare abstract class DepthFirstTraverser {
    static readonly getInstance: () => typeof DepthFirstTraverser.$metadata$.type;
    private constructor();
}
export declare namespace DepthFirstTraverser {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements Traverser {
            traverse(start: Visitable, selector: Selector, visitor: PortGraphVisitor): void;
            readonly __doNotUseOrImplementIt: Traverser["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare abstract class BreadthFirstTraverser {
    static readonly getInstance: () => typeof BreadthFirstTraverser.$metadata$.type;
    private constructor();
}
export declare namespace BreadthFirstTraverser {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements Traverser {
            traverse(start: Visitable, selector: Selector, visitor: PortGraphVisitor): void;
            readonly __doNotUseOrImplementIt: Traverser["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare class PortGraphVisitor {
    constructor();
    protected get NoOpExit(): () => void;
    visit(visitable: Visitable): () => void;
    protected visitGraph(graph: PortGraph<any /*UnknownType **/, any /*UnknownType **/>): () => void;
    protected visitNode(node: PortNode<any /*UnknownType **/>): () => void;
    protected visitConsumerPort(port: ConsumerPort<any /*UnknownType **/>): () => void;
    protected visitProviderPort(port: ProviderPort<any /*UnknownType **/>): () => void;
    protected visitEdge(edge: Edge<any /*UnknownType **/>): () => void;
}
export declare namespace PortGraphVisitor {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PortGraphVisitor;
    }
}
export declare class HierarchyVisitor extends PortGraphVisitor.$metadata$.constructor {
    constructor();
    get rootMap(): KtMutableMap<string, any>;
    set rootMap(value: KtMutableMap<string, any>);
    protected visitGraph(graph: PortGraph<any /*UnknownType **/, any /*UnknownType **/>): () => void;
    protected visitNode(node: PortNode<any /*UnknownType **/>): () => void;
    protected visitConsumerPort(port: ConsumerPort<any /*UnknownType **/>): () => void;
    protected visitProviderPort(port: ProviderPort<any /*UnknownType **/>): () => void;
    protected visitEdge(edge: Edge<any /*UnknownType **/>): () => void;
}
export declare namespace HierarchyVisitor {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => HierarchyVisitor;
    }
}