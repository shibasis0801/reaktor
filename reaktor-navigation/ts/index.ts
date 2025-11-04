import * as reaktor from "reaktor-reaktor-navigation";
import {Key, Nullable, Properties, Type, Node} from "reaktor-reaktor-navigation";
import {Consumer, PropsWithChildren} from "react";
import {Greeter} from "./karakum";

type Optional<T> = T | null | undefined;
type KeyType = { key: Key, type: Type };

declare module "reaktor-reaktor-navigation" {
    interface Node {
        useContract<Contract>(keyType: KeyType): Optional<Contract>;
    }
}


Node.prototype.useContract = function<Contract>(keyType: KeyType) {
    const consumer = this.getConsumer(keyType.key, keyType.type);
    return consumer?.contract as Optional<Contract>
}



export * from "reaktor-reaktor-navigation";
export {
    Greeter
}


