import com.codingfeline.buildkonfig.compiler.FieldSpec
import com.codingfeline.buildkonfig.gradle.BuildKonfigTask
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import java.net.InetSocketAddress
import java.net.Socket

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-io"))
        }
    }

    // https://web.dev/articles/origin-private-file-system
    // https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system
    web {
        dependencies = {

        }
    }

    droid {
        dependencies = {
            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }

    darwin {
        dependencies = {
            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }
    server {
        dependencies = {

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.db")
}