import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}
kotlin {
    common {
        dependencies {
            implementation("io.modelcontextprotocol:kotlin-sdk-client:0.7.2")
        }
    }
    droid {}
    darwin {}
    server {
        dependencies {
            implementation("io.modelcontextprotocol:kotlin-sdk-server:0.7.2")

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.mcp")
}