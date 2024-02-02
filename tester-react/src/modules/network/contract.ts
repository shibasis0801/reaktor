import {Flow} from "../../types/Flow";

export interface NetworkModule {
    get(): Flow<any>
}