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
export declare interface KtMutableMap<K, V> /* extends KtMap<K, V> */ {
    asJsMapView(): Map<K, V>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableMap": unique symbol;
    };
}
export declare namespace KtMutableMap {
    function fromJsMap<K, V>(map: ReadonlyMap<K, V>): KtMutableMap<K, V>;
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
    get nodes(): KtMutableList<N>/* ArrayList<N> */;
    attach(node: N): boolean;
    detach(node: N): boolean;
    close(): void;
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