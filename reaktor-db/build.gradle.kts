import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.db", targets = Targets.All) {
    val resources by webResources()
    val packageSources by webPackage("ts")
    val generateBindings by bindings()
    dependencies {
        common {
            api(Reaktor.Io)
            api(Versions.Data.SqlDelight)
            api(Versions.Data.Apollo)
        }
        web {
            api(Versions.Data.SqlDelightWeb)
            api(npm("@sqlite.org/sqlite-wasm", Versions.SQLiteWasm))
        }
        android {
            implementation(Versions.Android.SQLite)
            api(Versions.Data.SqlDelightAndroid)
        }
        apple { api(Versions.Data.SqlDelightNative) }
        jvm {
            implementation(Versions.Data.SqlDelightJdbc)
            implementation(Versions.Data.SqlDelightSQLite)
            api(Reaktor.Service)
            api(Versions.Data.Neo4j)
            implementation(Versions.Kotlin.CoroutinesJdk8)
            api(Versions.Data.Exposed)
            api(Versions.Data.ExposedJdbc)
            api(Versions.Data.Postgres)
        }
    }
}
