package dev.shibasis.reaktor.cloud.observability

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pulumi.Context
import com.pulumi.grafana.oss.Dashboard
import com.pulumi.grafana.oss.DashboardArgs
import com.pulumi.grafana.oss.DataSource
import com.pulumi.grafana.oss.DataSourceArgs
import java.util.Base64

private val gson = Gson()

class PubsubOutputs(val gcpDs: DataSource, val pubsubDashboard: Dashboard)

/** Mirrors src/pubsub.ts — the GCP Stackdriver datasource (from a base64 SA key) and the Pub/Sub dashboard. */
fun pubsub(ctx: Context, g: GrafanaContext): PubsubOutputs {
    val cfg = ctx.config("gcp")
    val project = cfg.require("project")
    val credentialsB64 = cfg.requireSecret("credentials")

    fun decode(b64: String): JsonObject =
        gson.fromJson(String(Base64.getDecoder().decode(b64), Charsets.UTF_8), JsonObject::class.java)

    val jsonData = credentialsB64.applyValue { b64 ->
        val c = decode(b64)
        gson.toJson(
            mapOf(
                "authenticationType" to "jwt",
                "defaultProject" to project,
                "clientEmail" to c.get("client_email").asString,
                "tokenUri" to c.get("token_uri").asString,
            ),
        )
    }
    val secureData = credentialsB64.applyValue { b64 ->
        gson.toJson(mapOf("privateKey" to decode(b64).get("private_key").asString))
    }

    val gcpDs = DataSource(
        "gcp-monitoring",
        DataSourceArgs.builder()
            .type("stackdriver")
            .name("gcp-monitoring")
            .jsonDataEncoded(jsonData)
            .secureJsonDataEncoded(secureData)
            .build(),
        g.opts,
    )

    val dashboardJson = resource("/dashboards/pubsub-overview.json")
    val pubsubDashboard = Dashboard(
        "pubsub-overview",
        DashboardArgs.builder()
            .configJson(
                gcpDs.uid().applyValue { uid ->
                    dashboardJson.replace("__GCP_DS__", uid).replace("__GCP_PROJECT__", project)
                },
            )
            .folder(g.folder.uid())
            .build(),
        g.opts,
    )

    return PubsubOutputs(gcpDs, pubsubDashboard)
}
