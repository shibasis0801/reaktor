/** FIFO with amortized constant-time removal; consumed objects are released immediately. */
export default class Queue<T> {
  private values: Array<T | undefined> = [];
  private head = 0;
  empty(): boolean { return this.head === this.values.length; }
  size(): number { return this.values.length - this.head; }
  enqueue(value: T): void { this.values.push(value); }
  dequeue(): T {
    if (this.empty()) throw new Error('Cannot dequeue an empty flow');
    const value = this.values[this.head] as T;
    this.values[this.head++] = undefined;
    if (this.empty()) { this.values = []; this.head = 0; }
    else if (this.head >= 1024 && this.head * 2 >= this.values.length) {
      this.values = this.values.slice(this.head);
      this.head = 0;
    }
    return value;
  }
}
