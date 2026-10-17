import Queue from './queue.ts';
export type Observer<T> = (value: T) => void;

/** Serial delivery permits reentrant emission and cancellation without dropping queued values. */
export class Collector<T> {
  private observer: Observer<T> | undefined;
  private readonly queue = new Queue<T>();
  private draining = false;

  collect(observer: Observer<T>): Observer<T> {
    if (this.observer) throw new Error('Flow does not support multiple observers');
    this.observer = observer;
    this.flush();
    return observer;
  }
  stopCollecting(observer: Observer<T>): void {
    if (this.observer === observer) this.stop();
  }
  stop(): void { this.observer = undefined; }
  emit(value: T): void { this.queue.enqueue(value); this.flush(); }
  flush(): void {
    if (this.draining) return;
    this.draining = true;
    try {
      while (this.observer && !this.queue.empty()) this.observer(this.queue.dequeue());
    } finally { this.draining = false; }
  }
}
