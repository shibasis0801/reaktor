import { NetworkModule } from "./contract";

// @ts-ignore
const MobileNetworkModule = () => global.NetworkModule as NetworkModule
export default MobileNetworkModule