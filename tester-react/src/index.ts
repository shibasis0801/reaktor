import { StateFlow } from './types/Flow'
import { NativeModules } from 'react-native'

export type Nullable<T> = T | null | undefined

export function install() {
    if (globalThis) {
        // @ts-ignore
        globalThis.Flow = StateFlow;
    }
    if (global) {
        // @ts-ignore
        global.Flow = StateFlow;
    }
    const { JSIManager } = NativeModules;
    JSIManager.install();
}