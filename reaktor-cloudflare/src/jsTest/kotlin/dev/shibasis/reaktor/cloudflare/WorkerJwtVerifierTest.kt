package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.*

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class WorkerJwtVerifierTest {
    private suspend fun fixture(): dynamic {
        val source = """async function(){
        const original={fetch:globalThis.fetch,caches:globalThis.caches,now:Date.now};
        let now=Date.now(), active=0, reads=0, puts=0, kvPuts=0;const kvData=new Map();
        const pairs=await Promise.all([0,1].map(()=>crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify'])));
        const keys=await Promise.all(pairs.map(p=>crypto.subtle.exportKey('jwk',p.publicKey)));
        const held=new Map();
        Date.now=()=>now;
        globalThis.caches={default:{async match(req){return held.get(req.url)?.clone()},async put(req,res){puts++;held.set(req.url,res.clone())}}};
        globalThis.fetch=async()=>{reads++;return Response.json({keys:[Object.assign({},keys[active],{kid:active?'new':'old',alg:'ES256'})]})};
        const segment=x=>btoa(JSON.stringify(x)).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
        return {get reads(){return reads},get puts(){return puts},get kvPuts(){return kvPuts},kv:{async get(k){return kvData.get(k)??null},async put(k,v){kvPuts++;kvData.set(k,v)}},held,rotate(){active=1},age(ms){now+=ms},restore(){Date.now=original.now;globalThis.fetch=original.fetch;globalThis.caches=original.caches},async token(kid){
            const header={alg:'ES256'};if(kid!==null)header.kid=kid;
            const input=segment(header)+'.'+segment({iss:'https://fixture.invalid',sub:'reader',exp:Math.floor(now/1000)+7200});
            const bytes=new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},pairs[active].privateKey,new TextEncoder().encode(input)));
            return input+'.'+btoa(String.fromCharCode(...bytes)).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
        }};
    }"""
        val make = js("Function")("return (" + source + ")()")
        return make().unsafeCast<Promise<dynamic>>().await()
    }

    private suspend fun token(f: dynamic, kid: String?): String = f.token(kid).unsafeCast<Promise<String>>().await()

    @Test fun aNewKidRefreshesASingleOldKey() = GlobalScope.promise {
        val f = fixture()
        try {
            val verifier = WorkerJwtVerifier("https://fixture.invalid", "https://fixture.invalid/new-kid")
            assertEquals("reader", verifier.verify(token(f, "old"))?.subject)
            f.rotate()
            assertEquals("reader", verifier.verify(token(f, "new"))?.subject)
            assertEquals(2, f.reads as Int)
        } finally { f.restore() }
    }

    @Test fun aReusedKidRefreshesAfterAShortAge() = GlobalScope.promise {
        val f = fixture()
        try {
            val verifier = WorkerJwtVerifier("https://fixture.invalid", "https://fixture.invalid/reused-kid")
            assertNotNull(verifier.verify(token(f, "old")))
            f.rotate(); f.age(61000)
            assertNotNull(verifier.verify(token(f, "old")))
            assertEquals(2, f.reads as Int)
        } finally { f.restore() }
    }

    @Test fun aTokenWithoutAKidRefreshesAfterAShortAge() = GlobalScope.promise {
        val f = fixture()
        try {
            val verifier = WorkerJwtVerifier("https://fixture.invalid", "https://fixture.invalid/no-kid")
            assertNotNull(verifier.verify(token(f, null)))
            f.rotate(); f.age(61000)
            assertNotNull(verifier.verify(token(f, null)))
            assertEquals(2, f.reads as Int)
        } finally { f.restore() }
    }

    @Test fun randomKidsThrottleTheNetworkAndCacheWrites() = GlobalScope.promise {
        val f = fixture()
        try {
            val verifier = WorkerJwtVerifier("https://fixture.invalid", "https://fixture.invalid/throttle")
            assertNotNull(verifier.verify(token(f, "old")))
            val random = (0 until 20).map { token(f, "random$it") }
            val another = token(f, "another")
            f.rotate()
            random.forEach { assertNull(verifier.verify(it)) }
            assertEquals(2, f.reads as Int)
            assertEquals(2, f.puts as Int)
            f.age(31000)
            assertNull(verifier.verify(another))
            assertEquals(3, f.reads as Int)
        } finally { f.restore() }
    }

    @Test fun aColoHitKeepsTheOriginalFetchAge() = GlobalScope.promise {
        val f = fixture()
        try {
            val first = "https://fixture.invalid/cache-first"
            assertNotNull(WorkerJwtVerifier("https://fixture.invalid", first).verify(token(f, "old")))
            f.age(3_590_000)
            val second = "https://fixture.invalid/cache-second"
            f.held.set(second, f.held.get(first).clone())
            val verifier = WorkerJwtVerifier("https://fixture.invalid", second)
            assertNotNull(verifier.verify(token(f, "old")))
            assertEquals(1, f.reads as Int)
            f.age(11000)
            assertNotNull(verifier.verify(token(f, "old")))
            assertEquals(2, f.reads as Int)
        } finally { f.restore() }
    }
    @Test fun aKvHitKeepsItsAgeAndRandomKidsCannotRestampIt() = GlobalScope.promise {
        val f = fixture()
        try {
            val kv = KvNamespace(f.kv.unsafeCast<RawKvNamespace>())
            assertNotNull(WorkerJwtVerifier("https://fixture.invalid", "https://fixture.invalid/kv-first", kv = kv).verify(token(f, "old")))
            f.held.clear(); f.age(3_590_000)
            val verifier = WorkerJwtVerifier("https://fixture.invalid", "https://fixture.invalid/kv-second", kv = kv)
            assertNotNull(verifier.verify(token(f, "old")))
            assertEquals(1, f.reads as Int)
            assertEquals(1, f.kvPuts as Int)
            f.age(11000)
            assertNotNull(verifier.verify(token(f, "old")))
            assertEquals(2, f.reads as Int)
            f.rotate()
            val random = (0 until 10).map { token(f, "missing$it") }
            random.forEach { assertNull(verifier.verify(it)) }
            assertEquals(2, f.reads as Int)
            assertEquals(2, f.kvPuts as Int)
        } finally { f.restore() }
    }

}
