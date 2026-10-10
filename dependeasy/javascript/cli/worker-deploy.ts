import { deploy } from '../api/cloudflare.ts';

deploy().catch(error => { console.error(error.message); process.exitCode = 1; });
