import { StateFlow, Flow } from './types/Flow'
export type Nullable<T> = T | null | undefined
export { StateFlow, Flow }

const flow = new StateFlow<number>();
flow.collect(print);

global.Flow = StateFlow;

[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].forEach(flow.emit);