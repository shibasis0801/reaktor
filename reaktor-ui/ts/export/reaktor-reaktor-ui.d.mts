type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
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
/** @deprecated  */
export declare const initHook: { get(): any; };
/** @deprecated  */
export declare const initHook: { get(): any; };
export declare abstract class ComponentSize {
    private constructor();
    static get Small(): ComponentSize & {
        get name(): "Small";
        get ordinal(): 0;
    };
    static get Medium(): ComponentSize & {
        get name(): "Medium";
        get ordinal(): 1;
    };
    static get Large(): ComponentSize & {
        get name(): "Large";
        get ordinal(): 2;
    };
    get name(): "Small" | "Medium" | "Large";
    get ordinal(): 0 | 1 | 2;
    static values(): Array<ComponentSize>;
    static valueOf(value: string): ComponentSize;
}
export declare namespace ComponentSize {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ComponentSize;
    }
}
export declare abstract class ComponentVariant {
    private constructor();
    static get Filled(): ComponentVariant & {
        get name(): "Filled";
        get ordinal(): 0;
    };
    static get Outlined(): ComponentVariant & {
        get name(): "Outlined";
        get ordinal(): 1;
    };
    static get Text(): ComponentVariant & {
        get name(): "Text";
        get ordinal(): 2;
    };
    static get Tonal(): ComponentVariant & {
        get name(): "Tonal";
        get ordinal(): 3;
    };
    static get Elevated(): ComponentVariant & {
        get name(): "Elevated";
        get ordinal(): 4;
    };
    get name(): "Filled" | "Outlined" | "Text" | "Tonal" | "Elevated";
    get ordinal(): 0 | 1 | 2 | 3 | 4;
    static values(): Array<ComponentVariant>;
    static valueOf(value: string): ComponentVariant;
}
export declare namespace ComponentVariant {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ComponentVariant;
    }
}
export declare abstract class ComponentState {
    private constructor();
    static get Enabled(): ComponentState & {
        get name(): "Enabled";
        get ordinal(): 0;
    };
    static get Disabled(): ComponentState & {
        get name(): "Disabled";
        get ordinal(): 1;
    };
    static get Loading(): ComponentState & {
        get name(): "Loading";
        get ordinal(): 2;
    };
    get name(): "Enabled" | "Disabled" | "Loading";
    get ordinal(): 0 | 1 | 2;
    static values(): Array<ComponentState>;
    static valueOf(value: string): ComponentState;
}
export declare namespace ComponentState {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ComponentState;
    }
}
export declare abstract class TextRole {
    private constructor();
    static get Display(): TextRole & {
        get name(): "Display";
        get ordinal(): 0;
    };
    static get Headline(): TextRole & {
        get name(): "Headline";
        get ordinal(): 1;
    };
    static get Title(): TextRole & {
        get name(): "Title";
        get ordinal(): 2;
    };
    static get Body(): TextRole & {
        get name(): "Body";
        get ordinal(): 3;
    };
    static get Label(): TextRole & {
        get name(): "Label";
        get ordinal(): 4;
    };
    static get Caption(): TextRole & {
        get name(): "Caption";
        get ordinal(): 5;
    };
    get name(): "Display" | "Headline" | "Title" | "Body" | "Label" | "Caption";
    get ordinal(): 0 | 1 | 2 | 3 | 4 | 5;
    static values(): Array<TextRole>;
    static valueOf(value: string): TextRole;
}
export declare namespace TextRole {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TextRole;
    }
}
export declare abstract class TextSize {
    private constructor();
    static get Small(): TextSize & {
        get name(): "Small";
        get ordinal(): 0;
    };
    static get Medium(): TextSize & {
        get name(): "Medium";
        get ordinal(): 1;
    };
    static get Large(): TextSize & {
        get name(): "Large";
        get ordinal(): 2;
    };
    get name(): "Small" | "Medium" | "Large";
    get ordinal(): 0 | 1 | 2;
    static values(): Array<TextSize>;
    static valueOf(value: string): TextSize;
}
export declare namespace TextSize {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TextSize;
    }
}
export declare const ReaktorUIDemo: { get(): FC<ReaktorUIDemoProps>; };
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
export declare namespace PromiseState {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PromiseState;
    }
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
export declare namespace PromiseResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <T>() => PromiseResult<T>;
    }
}
export declare function usePromise<T>(dependencies: Array<Nullable<any>>, promiseFactory: () => Nullable<Promise<T>>): PromiseResult<T>;
export declare class WebColorScheme {
    constructor(background: string, surface: string, surfaceVariant: string, surfaceContainer: string, surfaceContainerHigh: string, surfaceContainerLow: string, onBackground: string, onSurface: string, onSurfaceVariant: string, primary: string, primaryContainer: string, onPrimary: string, onPrimaryContainer: string, secondary: string, secondaryContainer: string, onSecondary: string, onSecondaryContainer: string, tertiary: string, tertiaryContainer: string, onTertiary: string, onTertiaryContainer: string, error: string, errorContainer: string, onError: string, onErrorContainer: string, success: string, onSuccess: string, warning: string, onWarning: string, info: string, onInfo: string, outline: string, outlineVariant: string, scrim: string, shadow: string, inverseSurface: string, inverseOnSurface: string, inversePrimary: string);
    get background(): string;
    get surface(): string;
    get surfaceVariant(): string;
    get surfaceContainer(): string;
    get surfaceContainerHigh(): string;
    get surfaceContainerLow(): string;
    get onBackground(): string;
    get onSurface(): string;
    get onSurfaceVariant(): string;
    get primary(): string;
    get primaryContainer(): string;
    get onPrimary(): string;
    get onPrimaryContainer(): string;
    get secondary(): string;
    get secondaryContainer(): string;
    get onSecondary(): string;
    get onSecondaryContainer(): string;
    get tertiary(): string;
    get tertiaryContainer(): string;
    get onTertiary(): string;
    get onTertiaryContainer(): string;
    get error(): string;
    get errorContainer(): string;
    get onError(): string;
    get onErrorContainer(): string;
    get success(): string;
    get onSuccess(): string;
    get warning(): string;
    get onWarning(): string;
    get info(): string;
    get onInfo(): string;
    get outline(): string;
    get outlineVariant(): string;
    get scrim(): string;
    get shadow(): string;
    get inverseSurface(): string;
    get inverseOnSurface(): string;
    get inversePrimary(): string;
    copy(background?: string, surface?: string, surfaceVariant?: string, surfaceContainer?: string, surfaceContainerHigh?: string, surfaceContainerLow?: string, onBackground?: string, onSurface?: string, onSurfaceVariant?: string, primary?: string, primaryContainer?: string, onPrimary?: string, onPrimaryContainer?: string, secondary?: string, secondaryContainer?: string, onSecondary?: string, onSecondaryContainer?: string, tertiary?: string, tertiaryContainer?: string, onTertiary?: string, onTertiaryContainer?: string, error?: string, errorContainer?: string, onError?: string, onErrorContainer?: string, success?: string, onSuccess?: string, warning?: string, onWarning?: string, info?: string, onInfo?: string, outline?: string, outlineVariant?: string, scrim?: string, shadow?: string, inverseSurface?: string, inverseOnSurface?: string, inversePrimary?: string): WebColorScheme;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebColorScheme {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebColorScheme;
    }
}
export declare class WebTextStyle {
    constructor(fontSize: string, lineHeight: string, fontWeight: string, letterSpacing: string);
    get fontSize(): string;
    get lineHeight(): string;
    get fontWeight(): string;
    get letterSpacing(): string;
    copy(fontSize?: string, lineHeight?: string, fontWeight?: string, letterSpacing?: string): WebTextStyle;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebTextStyle {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebTextStyle;
    }
}
export declare class WebTypography {
    constructor(displayLarge: WebTextStyle, displayMedium: WebTextStyle, displaySmall: WebTextStyle, headlineLarge: WebTextStyle, headlineMedium: WebTextStyle, headlineSmall: WebTextStyle, titleLarge: WebTextStyle, titleMedium: WebTextStyle, titleSmall: WebTextStyle, bodyLarge: WebTextStyle, bodyMedium: WebTextStyle, bodySmall: WebTextStyle, labelLarge: WebTextStyle, labelMedium: WebTextStyle, labelSmall: WebTextStyle);
    get displayLarge(): WebTextStyle;
    get displayMedium(): WebTextStyle;
    get displaySmall(): WebTextStyle;
    get headlineLarge(): WebTextStyle;
    get headlineMedium(): WebTextStyle;
    get headlineSmall(): WebTextStyle;
    get titleLarge(): WebTextStyle;
    get titleMedium(): WebTextStyle;
    get titleSmall(): WebTextStyle;
    get bodyLarge(): WebTextStyle;
    get bodyMedium(): WebTextStyle;
    get bodySmall(): WebTextStyle;
    get labelLarge(): WebTextStyle;
    get labelMedium(): WebTextStyle;
    get labelSmall(): WebTextStyle;
    copy(displayLarge?: WebTextStyle, displayMedium?: WebTextStyle, displaySmall?: WebTextStyle, headlineLarge?: WebTextStyle, headlineMedium?: WebTextStyle, headlineSmall?: WebTextStyle, titleLarge?: WebTextStyle, titleMedium?: WebTextStyle, titleSmall?: WebTextStyle, bodyLarge?: WebTextStyle, bodyMedium?: WebTextStyle, bodySmall?: WebTextStyle, labelLarge?: WebTextStyle, labelMedium?: WebTextStyle, labelSmall?: WebTextStyle): WebTypography;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebTypography {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebTypography;
    }
}
export declare class WebSpacing {
    constructor(none?: string, xxs?: string, xs?: string, sm?: string, md?: string, lg?: string, xl?: string, xxl?: string, xxxl?: string);
    get none(): string;
    get xxs(): string;
    get xs(): string;
    get sm(): string;
    get md(): string;
    get lg(): string;
    get xl(): string;
    get xxl(): string;
    get xxxl(): string;
    copy(none?: string, xxs?: string, xs?: string, sm?: string, md?: string, lg?: string, xl?: string, xxl?: string, xxxl?: string): WebSpacing;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebSpacing {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebSpacing;
    }
}
export declare class WebShapes {
    constructor(none?: string, xs?: string, sm?: string, md?: string, lg?: string, xl?: string, full?: string);
    get none(): string;
    get xs(): string;
    get sm(): string;
    get md(): string;
    get lg(): string;
    get xl(): string;
    get full(): string;
    copy(none?: string, xs?: string, sm?: string, md?: string, lg?: string, xl?: string, full?: string): WebShapes;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebShapes {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebShapes;
    }
}
export declare class WebElevation {
    constructor(none?: string, xs?: string, sm?: string, md?: string, lg?: string, xl?: string);
    get none(): string;
    get xs(): string;
    get sm(): string;
    get md(): string;
    get lg(): string;
    get xl(): string;
    copy(none?: string, xs?: string, sm?: string, md?: string, lg?: string, xl?: string): WebElevation;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebElevation {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebElevation;
    }
}
export declare class WebSizing {
    constructor(touchTargetMin?: string, iconXs?: string, iconSm?: string, iconMd?: string, iconLg?: string, iconXl?: string, buttonSm?: string, buttonMd?: string, buttonLg?: string, inputSm?: string, inputMd?: string, inputLg?: string, avatarSm?: string, avatarMd?: string, avatarLg?: string, avatarXl?: string);
    get touchTargetMin(): string;
    get iconXs(): string;
    get iconSm(): string;
    get iconMd(): string;
    get iconLg(): string;
    get iconXl(): string;
    get buttonSm(): string;
    get buttonMd(): string;
    get buttonLg(): string;
    get inputSm(): string;
    get inputMd(): string;
    get inputLg(): string;
    get avatarSm(): string;
    get avatarMd(): string;
    get avatarLg(): string;
    get avatarXl(): string;
    copy(touchTargetMin?: string, iconXs?: string, iconSm?: string, iconMd?: string, iconLg?: string, iconXl?: string, buttonSm?: string, buttonMd?: string, buttonLg?: string, inputSm?: string, inputMd?: string, inputLg?: string, avatarSm?: string, avatarMd?: string, avatarLg?: string, avatarXl?: string): WebSizing;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebSizing {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebSizing;
    }
}
export declare class WebBreakpoints {
    constructor(mobile?: number, tablet?: number, desktop?: number, largeDesktop?: number);
    get mobile(): number;
    get tablet(): number;
    get desktop(): number;
    get largeDesktop(): number;
    copy(mobile?: number, tablet?: number, desktop?: number, largeDesktop?: number): WebBreakpoints;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebBreakpoints {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebBreakpoints;
    }
}
export declare class WebMotion {
    constructor(durationInstant?: number, durationFast?: number, durationNormal?: number, durationSlow?: number, durationSlowest?: number);
    get durationInstant(): number;
    get durationFast(): number;
    get durationNormal(): number;
    get durationSlow(): number;
    get durationSlowest(): number;
    copy(durationInstant?: number, durationFast?: number, durationNormal?: number, durationSlow?: number, durationSlowest?: number): WebMotion;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebMotion {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebMotion;
    }
}
export declare class WebDesignTokens {
    constructor(colors: WebColorScheme, typography?: WebTypography, spacing?: WebSpacing, shapes?: WebShapes, elevation?: WebElevation, sizing?: WebSizing, breakpoints?: WebBreakpoints, motion?: WebMotion);
    get colors(): WebColorScheme;
    get typography(): WebTypography;
    get spacing(): WebSpacing;
    get shapes(): WebShapes;
    get elevation(): WebElevation;
    get sizing(): WebSizing;
    get breakpoints(): WebBreakpoints;
    get motion(): WebMotion;
    copy(colors?: WebColorScheme, typography?: WebTypography, spacing?: WebSpacing, shapes?: WebShapes, elevation?: WebElevation, sizing?: WebSizing, breakpoints?: WebBreakpoints, motion?: WebMotion): WebDesignTokens;
    toString(): string;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace WebDesignTokens {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebDesignTokens;
    }
}
export declare function defaultWebTypography(): WebTypography;
export declare function lighten(hex: string, fraction: number): string;
export declare function darken(hex: string, fraction: number): string;
export declare function autoContentColor(background: string, lightContent?: string, darkContent?: string): string;
export declare function withAlpha(hex: string, alpha: number): string;
export declare function createLightColorScheme(primary: string, secondary: string, tertiary?: string, background?: string, surface?: string, error?: string, success?: string, warning?: string, info?: string): WebColorScheme;
export declare function createDarkColorScheme(primary: string, secondary: string, tertiary?: string, background?: string, surface?: string, error?: string, success?: string, warning?: string, info?: string): WebColorScheme;
export declare function createWebDesignTokens(colors: WebColorScheme): WebDesignTokens;
export declare function createWebTokens(primary: string, secondary: string, tertiary?: Nullable<string>, darkMode?: boolean): WebDesignTokens;
export declare abstract class WebMaterialTokens {
    static readonly getInstance: () => typeof WebMaterialTokens.$metadata$.type;
    private constructor();
}
export declare namespace WebMaterialTokens {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor {
            get defaultLight(): WebDesignTokens;
            get defaultDark(): WebDesignTokens;
            get blueLight(): WebDesignTokens;
            get blueDark(): WebDesignTokens;
            get greenLight(): WebDesignTokens;
            get greenDark(): WebDesignTokens;
            get reaktorLight(): WebDesignTokens;
            get reaktorDark(): WebDesignTokens;
            private constructor();
        }
    }
}