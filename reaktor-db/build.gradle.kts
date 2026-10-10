import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.db") {
        common {
            dependencies {
                api(project(":reaktor-io"))
                api(Versions.Data.SqlDelight)
                api(Versions.Data.Apollo)
            }
        }

        web {
            dependencies {
                api(Versions.Data.SqlDelightWeb)
                api(npm("@sqlite.org/sqlite-wasm", Versions.SQLiteWasm))
            }
        }

        android {
            dependencies {
                implementation(Versions.Android.SQLite)
                api(Versions.Data.SqlDelightAndroid)
            }
        }

        apple {
            dependencies {
                api(Versions.Data.SqlDelightNative)
            }
        }

        jvm {
            dependencies {
                implementation(Versions.Data.SqlDelightJdbc)
                implementation(Versions.Data.SqlDelightSQLite)
                api(project(":reaktor-service"))
                api(Versions.Data.Neo4j)
                implementation(Versions.Kotlin.CoroutinesJdk8)
                // Exposed/Postgres for the service DB helpers (ExposedAdapter/CrudRepository).
                api(Versions.Data.Exposed)
                api(Versions.Data.ExposedJdbc)
                api(Versions.Data.Postgres)
            }
        }
    }


    tasks.withType<Test> {
        filter {
            includeTestsMatching("*Test")
            excludeTestsMatching("*TestUser*")
            excludeTestsMatching("*TestConfig*")
        }
    }

    // The React facade consumes the compiler's ES module, alongside the shipped worker resource.
    tasks.named("jsBrowserProductionLibraryDistribution") {
        doLast {
            copy {
                from(layout.buildDirectory.dir("compileSync/js/main/productionLibrary/kotlin"))
                into(layout.projectDirectory.dir("ts/export"))
                include("reaktor-reaktor-db.*")
            }
        }
    }
}
