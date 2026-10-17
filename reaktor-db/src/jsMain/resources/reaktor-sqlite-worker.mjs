import sqlite3InitModule from "./sqlite/sqlite3.mjs";

const name = new URL(self.location.href).searchParams.get("name");
const lockName = `reaktor-sqlite-${name}`;
let sqlite;
let pool;
let database;
let finishTransaction;
let transaction;
let pending = Promise.resolve();

async function open() {
    if (!/^[A-Za-z0-9_-]+$/.test(name || "")) throw new Error("Invalid database name");
    if (!navigator.storage?.getDirectory || !navigator.locks) {
        throw new Error("Persistent SQLite needs OPFS and Web Locks");
    }
    sqlite ||= await sqlite3InitModule();
    if (pool) await pool.unpauseVfs();
    else pool = await sqlite.installOpfsSAHPoolVfs({
        name: "reaktor-sqlite",
        directory: `.reaktor-sqlite-${name}`,
        initialCapacity: 6,
        clearOnInit: false,
    });
    try {
        database = new pool.OpfsSAHPoolDb(`/${name}.sqlite3`, "c");
    } catch (error) {
        pool.pauseVfs();
        throw error;
    }
}

function close() {
    try { database?.close(); }
    finally {
        database = undefined;
        pool?.pauseVfs();
    }
}

function execute(message) {
    const columns = [];
    const values = database.exec({
        sql: message.sql,
        bind: (message.params || []).map(value => ArrayBuffer.isView(value)
            ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : value),
        rowMode: "array",
        returnValue: "resultRows",
        columnNames: columns,
    });
    return { values: columns.length ? values : [[Number(database.changes())]] };
}

async function begin() {
    if (transaction) throw new Error("A transaction is already open");
    let started;
    let failed;
    const ready = new Promise((resolve, reject) => { started = resolve; failed = reject; });
    transaction = navigator.locks.request(lockName, async () => {
        try {
            await open();
            database.exec("BEGIN TRANSACTION");
            await new Promise(resolve => { finishTransaction = resolve; started(); });
        } catch (error) { failed(error); throw error; }
        finally { close(); }
    });
    transaction.catch(() => {});
    try { await ready; }
    catch (error) { transaction = undefined; throw error; }
    return { values: [[0]] };
}

async function end(commit) {
    if (!transaction) throw new Error("No transaction is open");
    try {
        database.exec(commit ? "COMMIT" : "ROLLBACK");
        return { values: [[0]] };
    } finally {
        finishTransaction();
        await transaction;
        transaction = undefined;
        finishTransaction = undefined;
    }
}

async function handle(message) {
    switch (message.action) {
        case "exec":
            if (transaction) return execute(message);
            // Pause the pool between statements so other tabs can acquire the same OPFS files.
            return navigator.locks.request(lockName, async () => {
                try { await open(); return execute(message); }
                finally { close(); }
            });
        case "begin_transaction": return begin();
        case "end_transaction": return end(true);
        case "rollback_transaction": return end(false);
        default: throw new Error(`Unknown SQLDelight action: ${message.action}`);
    }
}

self.onmessage = ({ data }) => {
    pending = pending.then(async () => {
        try { self.postMessage({ id: data.id, results: await handle(data) }); }
        catch (error) { self.postMessage({ id: data.id, error: { name: error.name, message: error.message } }); }
    });
};
