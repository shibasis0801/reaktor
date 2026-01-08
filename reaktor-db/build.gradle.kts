import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

val sqldelightVersion: String by project

kotlin {
    common {
        dependencies {
            api(project(":reaktor-io"))
            api("app.cash.sqldelight:runtime:$sqldelightVersion")
        }
    }

    // https://web.dev/articles/origin-private-file-system
    // https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system
    web {
        dependencies {
            api("app.cash.sqldelight:web-worker-driver:$sqldelightVersion")
            implementation(devNpm("copy-webpack-plugin", "9.1.0"))
        }
    }

    droid {
        dependencies {
            implementation("androidx.sqlite:sqlite-framework:2.4.0")
            api("app.cash.sqldelight:android-driver:$sqldelightVersion")
        }
    }

    darwin {
        dependencies {
            api("app.cash.sqldelight:native-driver:$sqldelightVersion")
        }
        podDependencies {
            framework {
                linkerOpts("-lsqlite3")
            }
        }
    }
    server {
        dependencies {
            implementation("app.cash.sqldelight:jdbc-driver:$sqldelightVersion")
            implementation("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.db")
}
