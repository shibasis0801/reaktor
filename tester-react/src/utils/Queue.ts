export default class Queue<T> {
    values: T[] = [];

    empty = (): boolean => {
        return this.size() === 0;
    }

    size = (): number => {
        return this.values.length;
    }

    enqueue = (value: T) => {
        this.values.push(value);
    }

    /* Check if !Q.empty() */
    dequeue = (): T => {
        return this.values.shift()!;
    }
}
