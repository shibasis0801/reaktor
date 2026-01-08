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
export declare interface KtMap<K, V> {
    asJsReadonlyMapView(): ReadonlyMap<K, V>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMap": unique symbol;
    };
}
export declare namespace KtMap {
    function fromJsMap<K, V>(map: ReadonlyMap<K, V>): KtMap<K, V>;
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
/** @deprecated  */
export declare const initHook: { get(): any; };
export declare abstract class FileAdapter<Controller> /* extends Adapter<Controller> */ {
    constructor(controller: Controller);
    abstract get cacheDirectory(): string;
    abstract get documentDirectory(): string;
    resolvePath(fileName: string, directory?: string): string;
    exists(path: string): boolean;
    delete(path: string): void;
    copy(sourcePath: string, destPath: string): void;
    readBinaryFile(path: string): Nullable<Int8Array>;
    readTextFile(path: string): Nullable<string>;
    writeTextFile(path: string, data: string): void;
    writeBinaryFile(path: string, data: Int8Array): void;
}
export declare namespace FileAdapter {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Controller>() => FileAdapter<Controller>;
    }
}
export declare abstract class SqlAdapter<Controller> /* extends Adapter<Controller> */ {
    constructor(controller: Controller, dbName?: string, fileAdapter?: FileAdapter<any /*UnknownType **/>);
    get dbName(): string;
    get fileAdapter(): FileAdapter<any /*UnknownType **/>;
    protected abstract createDriver(): any/* SqlDriver */;
    getDriver(): any/* SqlDriver */;
    closeDriver(): void;
    transaction<T>(body: () => T): T;
    execute(statement: Statement): bigint;
    executeRaw(sql: string, args: Array<Nullable<any>>): bigint;
    checkSize(): bigint;
    vacuum(): void;
    backup(backupName: string): void;
    restore(backupName: string): void;
}
export declare namespace SqlAdapter {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <Controller>() => SqlAdapter<Controller>;
    }
}
export declare class SyncAdapter {
    constructor(client: any/* HttpClient */, sqlAdapter: SqlAdapter<any /*UnknownType **/>, fileAdapter: FileAdapter<any /*UnknownType **/>);
    upload(uploadUrl: string, snapshotName?: string): Promise<void>;
    download(downloadUrl: string, restoreName?: string): Promise<void>;
}
export declare namespace SyncAdapter {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SyncAdapter;
    }
}
export declare interface SqlType<T> {
    readonly sqlString: string;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.db.sql.SqlType": unique symbol;
    };
}
export declare abstract class IntegerType {
    static readonly getInstance: () => typeof IntegerType.$metadata$.type;
    private constructor();
}
export declare namespace IntegerType {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements SqlType<number> {
            get sqlString(): string;
            readonly __doNotUseOrImplementIt: SqlType<number>["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare abstract class TextType {
    static readonly getInstance: () => typeof TextType.$metadata$.type;
    private constructor();
}
export declare namespace TextType {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements SqlType<string> {
            get sqlString(): string;
            readonly __doNotUseOrImplementIt: SqlType<string>["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare abstract class BooleanType {
    static readonly getInstance: () => typeof BooleanType.$metadata$.type;
    private constructor();
}
export declare namespace BooleanType {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements SqlType<boolean> {
            get sqlString(): string;
            readonly __doNotUseOrImplementIt: SqlType<boolean>["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare abstract class DoubleType {
    static readonly getInstance: () => typeof DoubleType.$metadata$.type;
    private constructor();
}
export declare namespace DoubleType {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements SqlType<number> {
            get sqlString(): string;
            readonly __doNotUseOrImplementIt: SqlType<number>["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare abstract class BlobType {
    static readonly getInstance: () => typeof BlobType.$metadata$.type;
    private constructor();
}
export declare namespace BlobType {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor implements SqlType<Int8Array> {
            get sqlString(): string;
            readonly __doNotUseOrImplementIt: SqlType<Int8Array>["__doNotUseOrImplementIt"];
            private constructor();
        }
    }
}
export declare class ColumnDefinition {
    constructor(isPrimaryKey?: boolean, isAutoIncrement?: boolean, isNullable?: boolean, defaultValue?: Nullable<string>);
    get isPrimaryKey(): boolean;
    get isAutoIncrement(): boolean;
    get isNullable(): boolean;
    get defaultValue(): Nullable<string>;
    copy(isPrimaryKey?: boolean, isAutoIncrement?: boolean, isNullable?: boolean, defaultValue?: Nullable<string>): ColumnDefinition;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace ColumnDefinition {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ColumnDefinition;
    }
}
export declare abstract class Table {
    constructor(tableName: string);
    get tableName(): string;
    get columns(): KtList<Column<any /*UnknownType **/>>;
    protected integer(name: string, primaryKey?: boolean, autoIncrement?: boolean, nullable?: boolean, _default?: Nullable<number>): Column<number>;
    protected text(name: string, primaryKey?: boolean, nullable?: boolean, _default?: Nullable<string>): Column<string>;
    protected bool(name: string, nullable?: boolean, _default?: Nullable<boolean>): Column<boolean>;
    protected double(name: string, nullable?: boolean, _default?: Nullable<number>): Column<number>;
    protected blob(name: string, nullable?: boolean): Column<Int8Array>;
}
export declare namespace Table {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Table;
    }
}
export declare class Column<T> {
    constructor(name: string, type: SqlType<T>, table: Table, definition: ColumnDefinition);
    get name(): string;
    get type(): SqlType<T>;
    get table(): Table;
    get definition(): ColumnDefinition;
    copy(name?: string, type?: SqlType<T>, table?: Table, definition?: ColumnDefinition): Column<T>;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace Column {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <T>() => Column<T>;
    }
}
export declare abstract class Expression {
    protected constructor();
}
export declare namespace Expression {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Expression;
    }
    class Eq<T> extends Expression.$metadata$.constructor {
        constructor(column: Column<T>, value: T);
        get column(): Column<T>;
        get value(): T;
        copy(column?: Column<T>, value?: T): Expression.Eq<T>;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Eq {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new <T>() => Eq<T>;
        }
    }
    class Neq<T> extends Expression.$metadata$.constructor {
        constructor(column: Column<T>, value: T);
        get column(): Column<T>;
        get value(): T;
        copy(column?: Column<T>, value?: T): Expression.Neq<T>;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Neq {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new <T>() => Neq<T>;
        }
    }
    class Gt<T> extends Expression.$metadata$.constructor {
        constructor(column: Column<T>, value: T);
        get column(): Column<T>;
        get value(): T;
        copy(column?: Column<T>, value?: T): Expression.Gt<T>;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Gt {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new <T>() => Gt<T>;
        }
    }
    class Lt<T> extends Expression.$metadata$.constructor {
        constructor(column: Column<T>, value: T);
        get column(): Column<T>;
        get value(): T;
        copy(column?: Column<T>, value?: T): Expression.Lt<T>;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Lt {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new <T>() => Lt<T>;
        }
    }
    class Gte<T> extends Expression.$metadata$.constructor {
        constructor(column: Column<T>, value: T);
        get column(): Column<T>;
        get value(): T;
        copy(column?: Column<T>, value?: T): Expression.Gte<T>;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Gte {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new <T>() => Gte<T>;
        }
    }
    class Lte<T> extends Expression.$metadata$.constructor {
        constructor(column: Column<T>, value: T);
        get column(): Column<T>;
        get value(): T;
        copy(column?: Column<T>, value?: T): Expression.Lte<T>;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Lte {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new <T>() => Lte<T>;
        }
    }
    class Like extends Expression.$metadata$.constructor {
        constructor(column: Column<string>, value: string);
        get column(): Column<string>;
        get value(): string;
        copy(column?: Column<string>, value?: string): Expression.Like;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Like {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Like;
        }
    }
    class And extends Expression.$metadata$.constructor {
        constructor(left: Expression, right: Expression);
        get left(): Expression;
        get right(): Expression;
        copy(left?: Expression, right?: Expression): Expression.And;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace And {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => And;
        }
    }
    class Or extends Expression.$metadata$.constructor {
        constructor(left: Expression, right: Expression);
        get left(): Expression;
        get right(): Expression;
        copy(left?: Expression, right?: Expression): Expression.Or;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Or {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Or;
        }
    }
    abstract class Empty extends KtSingleton<Empty.$metadata$.constructor>() {
        private constructor();
    }
    namespace Empty {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor extends Expression.$metadata$.constructor {
                private constructor();
            }
        }
    }
}
export declare interface Statement {
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: {
        readonly "dev.shibasis.reaktor.db.sql.Statement": unique symbol;
    };
}
export declare class RenderResult {
    constructor(sql: string, args: Array<Nullable<any>>);
    get sql(): string;
    get args(): Array<Nullable<any>>;
    copy(sql?: string, args?: Array<Nullable<any>>): RenderResult;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace RenderResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RenderResult;
    }
}
export declare class CreateTableStatement /* extends BaseStatement */ implements Statement {
    constructor(table: Table);
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: Statement["__doNotUseOrImplementIt"];
}
export declare namespace CreateTableStatement {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CreateTableStatement;
    }
}
export declare class DropTableStatement /* extends BaseStatement */ implements Statement {
    constructor(table: Table);
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: Statement["__doNotUseOrImplementIt"];
}
export declare namespace DropTableStatement {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DropTableStatement;
    }
}
export declare class SelectStatement /* extends BaseStatement */ implements Statement {
    constructor(table: Table, columns: KtList<Column<any /*UnknownType **/>>, where: Expression, limit?: Nullable<number>, offset?: Nullable<number>);
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: Statement["__doNotUseOrImplementIt"];
}
export declare namespace SelectStatement {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SelectStatement;
    }
}
export declare class InsertStatement /* extends BaseStatement */ implements Statement {
    constructor(table: Table, values: KtMap<Column<any /*UnknownType **/>, Nullable<any>>);
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: Statement["__doNotUseOrImplementIt"];
}
export declare namespace InsertStatement {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InsertStatement;
    }
}
export declare class UpdateStatement /* extends BaseStatement */ implements Statement {
    constructor(table: Table, values: KtMap<Column<any /*UnknownType **/>, Nullable<any>>, where: Expression);
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: Statement["__doNotUseOrImplementIt"];
}
export declare namespace UpdateStatement {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UpdateStatement;
    }
}
export declare class DeleteStatement /* extends BaseStatement */ implements Statement {
    constructor(table: Table, where: Expression);
    renderSql(): string;
    renderArgs(): Array<Nullable<any>>;
    readonly __doNotUseOrImplementIt: Statement["__doNotUseOrImplementIt"];
}
export declare namespace DeleteStatement {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DeleteStatement;
    }
}
export declare abstract class SqlBuilder {
    static readonly getInstance: () => typeof SqlBuilder.$metadata$.type;
    private constructor();
}
export declare namespace SqlBuilder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor {
            create(table: Table): CreateTableStatement;
            drop(table: Table): DropTableStatement;
            select(columns: Array<Column<any /*UnknownType **/>>): SelectBuilder;
            insert(table: Table): InsertBuilder;
            update(table: Table): UpdateBuilder;
            delete(table: Table): DeleteBuilder;
            private constructor();
        }
    }
}
export declare class SelectBuilder {
    constructor(columns: KtList<Column<any /*UnknownType **/>>);
    from(table: Table): SelectFromBuilder;
}
export declare namespace SelectBuilder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SelectBuilder;
    }
}
export declare class SelectFromBuilder {
    constructor(table: Table, columns: KtList<Column<any /*UnknownType **/>>);
    where(expression: Expression): SelectStatement;
    all(): SelectStatement;
    limit(limit: number, offset?: number): SelectStatement;
}
export declare namespace SelectFromBuilder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SelectFromBuilder;
    }
}
export declare class InsertBuilder {
    constructor(table: Table);
    set<T>(column: Column<T>, value: T): InsertBuilder;
    build(): InsertStatement;
}
export declare namespace InsertBuilder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InsertBuilder;
    }
}
export declare class UpdateBuilder {
    constructor(table: Table);
    set<T>(column: Column<T>, value: T): UpdateBuilder;
    where(expression: Expression): UpdateStatement;
}
export declare namespace UpdateBuilder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UpdateBuilder;
    }
}
export declare class DeleteBuilder {
    constructor(table: Table);
    where(expression: Expression): DeleteStatement;
    all(): DeleteStatement;
}
export declare namespace DeleteBuilder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DeleteBuilder;
    }
}