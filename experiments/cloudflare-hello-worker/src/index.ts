import { Hono } from "hono"

;(globalThis as { HonoFactory?: typeof Hono }).HonoFactory = Hono

const { HelloWorker, HelloCounterDurableObject, ChatRoomDurableObject } = await import("reaktor-hello-worker-kotlin")

export default HelloWorker.getInstance()
export { HelloCounterDurableObject, ChatRoomDurableObject }
