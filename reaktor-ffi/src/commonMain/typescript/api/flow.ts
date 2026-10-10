import { Collector, type Observer } from '../kernel/collector.ts';
export { default } from '../kernel/queue.ts';

export interface Flow<T> {
  emit(value: T): void;
  stop(): void;
  collect(observer: Observer<T>): Observer<T>;
  stopCollecting(observer: Observer<T>): void;
}

/** A single subscriber stream; values emitted while detached wait for the next collector. */
export class StateFlow<T> implements Flow<T> {
  private readonly kernel = new Collector<T>();
  emit(value: T): void { this.kernel.emit(value); }
  stop(): void { this.kernel.stop(); }
  collect(observer: Observer<T>): Observer<T> { return this.kernel.collect(observer); }
  stopCollecting(observer: Observer<T>): void { this.kernel.stopCollecting(observer); }
  flushQueue(): void { this.kernel.flush(); }
}
