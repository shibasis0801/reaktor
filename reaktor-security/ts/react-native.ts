export * from "./index";

export interface ReactNativeSecurityNativeModule {
  createEngine(config: Uint8Array): number;
  destroyEngine(handle: number): void;
}
