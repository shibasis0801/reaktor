import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies = {
            commonCoroutines()
        }
    }

    web {
        dependencies = {
            api(npm("@cloudflare/workers-types", "4.20240512.0"))
        }
    }
}
