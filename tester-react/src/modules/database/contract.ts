import {Flow} from "../../types/Flow";

export type BatcavePageResponseWrapper = {
    page: string
    isStaled: boolean
    isCachedResponse: boolean
    retainWhileRefreshing: boolean
}

export type BatcaveLEError = {
    code: string
    message: string
}


export interface LayoutDatabase {
    getPage(
        uri: string,
        parentPageUri: string,
        //
        headers: string,
        body: string,
        //
        pageNumber: number,
        cacheKey: string,
        pageContextCacheKey: string,
        //
        forceFetch: boolean,
        isBackNavigation: boolean,
    ): Flow<BatcavePageResponseWrapper | BatcaveLEError>
}

