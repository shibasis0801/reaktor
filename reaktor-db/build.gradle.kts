import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.db") {
        common {
            dependencies {
                api(projects.reaktorIo)
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
                api(projects.reaktorService)
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

}
