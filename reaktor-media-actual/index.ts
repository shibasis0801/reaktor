import { fetch } from "./build/dist/web/productionLibrary/index.mjs"
import { R2Bucket, URL, Headers, D1Database, ExecutionContext } from "@cloudflare/workers-types"
export interface Env {
	// Example binding to KV. Learn more at https://developers.cloudflare.com/workers/runtime-apis/kv/
	// MY_KV_NAMESPACE: KVNamespace;
	//
	// Example binding to Durable Object. Learn more at https://developers.cloudflare.com/workers/runtime-apis/durable-objects/
	// MY_DURABLE_OBJECT: DurableObjectNamespace;
	//
	// Example binding to R2. Learn more at https://developers.cloudflare.com/workers/runtime-apis/r2/
	FILE: R2Bucket;
	DB: D1Database;
	//
	// Example binding to a Service. Learn more at https://developers.cloudflare.com/workers/runtime-apis/service-bindings/
	// MY_SERVICE: Fetcher;
	//
	// Example binding to a Queue. Learn more at https://developers.cloudflare.com/queues/javascript-apis/
	// MY_QUEUE: Queue;
}

export default {
	fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
		// request.arrayBuffer()
		// request.formData().then((form) => {
		// 	(form.get("file") as File).arrayBuffer()
		// })
		return fetch(request, env, ctx);
	},
};
