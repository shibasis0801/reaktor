import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.common.*

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
            api(npm("hono", "4.9.8"))
        }
    }
}
