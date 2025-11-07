import * as reaktor from "reaktor-reaktor-navigation";
import {Key, Nullable, Properties, Type, Node, KeyType} from "reaktor-reaktor-navigation";
import {Consumer, PropsWithChildren} from "react";
import {Greeter} from "./karakum";

export type Optional<T> = T | null | undefined;

declare module "reaktor-reaktor-navigation" {
    interface Node {
        getContract<Contract>(keyType: KeyType): Optional<Contract>;
    }
}

Node.prototype.getContract = function<Contract>(keyType: KeyType) {
    return this.getConsumer<Contract>(keyType)?.contract;
}


export * from "reaktor-reaktor-navigation";
export {
    Greeter
}


