package dev.shibasis.reaktor.tooling.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiSurfaceTest {
    private val cloudflare = ApiSpecs.openApi("cloudflare", Json.parseToJsonElement("""
        {"openapi":"3.0.3","info":{"title":"Cloudflare API","version":"4.0.0"},
         "servers":[{"url":"https://api.cloudflare.com/client/v4"}],
         "components":{"parameters":{"account":{"name":"account_id","in":"path","required":true,"schema":{"type":"string"}}},
           "schemas":{"Script":{"type":"object","properties":{"id":{"type":"string","description":"Name of the script"},"etag":{"type":"string"}}},
                      "Update":{"type":"object","properties":{"script":{"${'$'}ref":"#/components/schemas/Script"}}}}},
         "paths":{
           "/accounts/{account_id}/workers/scripts":{"parameters":[{"${'$'}ref":"#/components/parameters/account"}],
             "get":{"operationId":"worker-script-list-workers","summary":"List Workers","tags":["Worker Script"],"x-api-token-group":["Workers Scripts Read","Workers Scripts Write"]}},
           "/accounts/{account_id}/workers/scripts/{script_name}":{"parameters":[{"${'$'}ref":"#/components/parameters/account"},{"name":"script_name","in":"path","required":true,"schema":{"type":"string"}}],
             "put":{"operationId":"worker-script-upload-worker-module","summary":"Upload Worker Module","requestBody":{"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Update"}}}}},
             "delete":{"operationId":"worker-script-delete-worker","summary":"Delete Worker","parameters":[{"name":"force","in":"query","schema":{"type":"boolean"}}]}},
           "/graphql":{"post":{"operationId":"graphql-query","summary":"GraphQL analytics"}},
           "/zones":{"get":{"operationId":"zones-get","summary":"List Zones","parameters":[{"name":"name","in":"query","schema":{"type":"string"}},{"name":"page","in":"query","schema":{"type":"number"}}]}}}}
    """).jsonObject, "digest-1")

    private val pubsub = ApiSpecs.discovery(Json.parseToJsonElement("""
        {"name":"pubsub","version":"v1","title":"Cloud Pub/Sub API","rootUrl":"https://pubsub.googleapis.com/","servicePath":"",
         "parameters":{"fields":{"type":"string","location":"query"},"quotaUser":{"type":"string","location":"query"}},
         "schemas":{"Topic":{"type":"object","properties":{"name":{"type":"string"}}}},
         "resources":{"projects":{"resources":{"topics":{"methods":{
           "list":{"id":"pubsub.projects.topics.list","httpMethod":"GET","path":"v1/{+project}/topics","flatPath":"v1/projects/{projectsId}/topics",
             "parameters":{"project":{"type":"string","location":"path","required":true},"pageToken":{"type":"string","location":"query"}},
             "scopes":["https://www.googleapis.com/auth/cloud-platform","https://www.googleapis.com/auth/pubsub"]},
           "create":{"id":"pubsub.projects.topics.create","httpMethod":"PUT","path":"v1/{+name}","parameters":{"name":{"type":"string","location":"path","required":true}},"request":{"${'$'}ref":"Topic"}},
           "getIamPolicy":{"id":"pubsub.projects.topics.getIamPolicy","httpMethod":"GET","path":"v1/{+resource}:getIamPolicy","parameters":{"resource":{"type":"string","location":"path","required":true}}},
           "delete":{"id":"pubsub.projects.topics.delete","httpMethod":"DELETE","path":"v1/{+topic}","parameters":{"topic":{"type":"string","location":"path","required":true}}}}}}}}}
    """).jsonObject, "digest-2")

    @Test fun anOpenApiDocumentBecomesOneOperationPerPathAndMethod() {
        assertEquals(listOf("worker-script-list-workers", "worker-script-upload-worker-module", "worker-script-delete-worker", "graphql-query", "zones-get"),
            cloudflare.operations.map { it.id })
        val list = cloudflare["worker-script-list-workers"]!!
        assertEquals(listOf("account_id"), list.parameters.map { it.name }, "path-level parameters are inherited, through their \$ref")
        assertEquals(ApiEffect.Read, list.effect)
        assertEquals(ApiEffect.Write, cloudflare["worker-script-upload-worker-module"]!!.effect)
        assertEquals(ApiEffect.Delete, cloudflare["worker-script-delete-worker"]!!.effect)
        assertEquals(ApiEffect.Read, cloudflare["graphql-query"]!!.effect, "Cloudflare's GraphQL analytics endpoint only reads")
        assertEquals("cloudflare:zones-get", cloudflare["cloudflare:zones-get"]!!.key)
    }

    @Test fun aDiscoveryDocumentKeepsGoogleMethodIdsAndReservedPaths() {
        assertEquals(listOf("pubsub.projects.topics.list", "pubsub.projects.topics.create", "pubsub.projects.topics.getIamPolicy", "pubsub.projects.topics.delete"),
            pubsub.operations.map { it.id })
        val list = pubsub["pubsub.projects.topics.list"]!!
        assertEquals("v1/{+project}/topics", list.path, "a flat path that renames the parameters is not used")
        assertEquals(ApiEffect.Read, pubsub["pubsub.projects.topics.getIamPolicy"]!!.effect)
        assertEquals(ApiEffect.Write, pubsub["pubsub.projects.topics.create"]!!.effect)
        assertEquals(ApiEffect.Delete, pubsub["pubsub.projects.topics.delete"]!!.effect)
        val request = list.request(buildJsonObject { put("project", "projects/mehmaan-app"); put("pageToken", "a b") })
        assertEquals("https://pubsub.googleapis.com/v1/projects/mehmaan-app/topics?pageToken=a%20b", request.fullUrl(), "a reserved parameter keeps its slashes")
    }

    @Test fun argumentsBecomeTheRequestAndMistakesAreNamed() {
        val upload = cloudflare["worker-script-upload-worker-module"]!!
        val request = upload.request(buildJsonObject {
            put("account_id", "acct"); put("script_name", "bot service")
            putJsonObject("body") { put("main_module", "index.js") }
        })
        assertEquals("PUT", request.method)
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acct/workers/scripts/bot%20service", request.fullUrl())
        assertEquals("""{"main_module":"index.js"}""", request.body)
        val missing = assertFailsWith<ApiArgumentException> { upload.request(buildJsonObject { put("account_id", "acct") }) }
        assertEquals("cloudflare:worker-script-upload-worker-module needs script_name", missing.message)
        val unknown = assertFailsWith<ApiArgumentException> { cloudflare["zones-get"]!!.request(buildJsonObject { put("zone", "x") }) }
        assertTrue("unknown: zone" in unknown.message.orEmpty() && "name" in unknown.message.orEmpty())
        val zones = cloudflare["zones-get"]!!.request(buildJsonObject { put("name", "bestbuds.ai"); put("page", 2) })
        assertEquals("https://api.cloudflare.com/client/v4/zones?name=bestbuds.ai&page=2", zones.fullUrl())
    }

    @Test fun searchRanksTheOperationWhoseNameMatches() {
        assertEquals("worker-script-list-workers", cloudflare.search("list workers").first().id)
        assertEquals("pubsub.projects.topics.list", pubsub.search("topics list").first().id)
        assertEquals(emptyList(), cloudflare.search("vectorize indexes"))
    }

    @Test fun describeResolvesTheBodySchemaToAUsableDepth() {
        val described = cloudflare.describe("worker-script-upload-worker-module")!!
        val body = described["body"]!!.jsonObject
        val script = body["properties"]!!.jsonObject["script"]!!.jsonObject
        assertEquals(JsonPrimitive("Name of the script"), script["properties"]!!.jsonObject["id"]!!.jsonObject["description"])
        assertEquals(JsonPrimitive("write"), described["effect"])
        assertEquals(JsonPrimitive("Topic"), (pubsub.describe("pubsub.projects.topics.create", depth = 0)!!["body"]))
    }

    @Test fun eachOperationNamesThePermissionsThatAllowIt() {
        assertEquals(listOf("Workers Scripts Read", "Workers Scripts Write"), cloudflare["worker-script-list-workers"]!!.permissions)
        assertEquals(JsonArray(listOf(JsonPrimitive("Workers Scripts Read"), JsonPrimitive("Workers Scripts Write"))),
            cloudflare.describe("worker-script-list-workers")!!["permissions"])
        assertEquals(listOf("cloud-platform", "pubsub"), pubsub["pubsub.projects.topics.list"]!!.permissions)
        val supabase = ApiSpecs.openApi("supabase", Json.parseToJsonElement("""
            {"openapi":"3.0.0","info":{"title":"Supabase API","version":"1.0.0"},"servers":[{"url":"https://api.supabase.com"}],
             "paths":{"/v1/projects":{"get":{"operationId":"v1-list-all-projects","summary":"List all projects","x-oauth-scope":"projects:read"}}}}
        """).jsonObject, "digest-3")
        assertEquals(listOf("projects:read"), supabase["v1-list-all-projects"]!!.permissions)
        assertEquals(emptyList(), cloudflare["zones-get"]!!.permissions)
    }

    @Test fun percentEncodingIsTheSameOnEveryPlatform() {
        assertEquals("caf%C3%A9%2Fbar", percentEncode("café/bar"))
        assertEquals("a/b%20c", percentEncode("a/b c", keepSlash = true))
    }
}
