const Wake = "reaktor-work";
const AnswerWithin = 25_000;

function ask(page) {
    return new Promise((resolve, reject) => {
        const channel = new MessageChannel();
        const timer = setTimeout(() => reject(new Error("The page did not drain in time")), AnswerWithin);
        channel.port1.onmessage = ({ data }) => {
            clearTimeout(timer);
            if (data === "drained") resolve();
            else reject(new Error(String(data)));
        };
        page.postMessage({ type: Wake }, [channel.port2]);
    });
}

async function relay() {
    const pages = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    if (pages.length === 0) throw new Error("No open page can drain Reaktor work");
    await Promise.any(pages.map(ask));
}

self.addEventListener("sync", event => { if (event.tag === Wake) event.waitUntil(relay()); });
self.addEventListener("periodicsync", event => { if (event.tag === Wake) event.waitUntil(relay()); });
self.addEventListener("push", event => { if (event.data?.text() === Wake) event.waitUntil(relay()); });
