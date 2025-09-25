import { greet } from 'reaktor-ts-core'

export default {
  async fetch(): Promise<Response> {
    return new Response(greet('Cloudflare'))
  },
}
