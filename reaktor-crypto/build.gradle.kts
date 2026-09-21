import dev.shibasis.dependeasy.android.defaults
import dev.shibasis.dependeasy.android.droid
import dev.shibasis.dependeasy.common.common
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.server.server
import dev.shibasis.dependeasy.web.web

plugins {
    id("dev.shibasis.dependeasy.library")
}

// Portable primitives, and nothing else: no key storage, no protocol, no opinions about what is
// being encrypted. `reaktor-security` is the MLS implementation and reaches Android, JVM and the
// Apple platforms through C++ and OpenSSL; it has no `jsMain` and cannot get one, so anything that
// has to run in a browser or a Worker — which for a Kotlin Multiplatform app is half its targets —
// had no crypto at all before this module.
kotlin {
    common {
        dependencies {
            commonCoroutines()
        }
        testDependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${dev.shibasis.dependeasy.Version.Coroutines}")
        }
    }
    droid {}
    server {}
    web {}

    sourceSets {
        // Android and the JVM are the same implementation on JCA. A shared directory rather than a
        // copy in each, because two copies of a cipher is two places for one of them to drift.
        val jvmShared = "src/jvmShared/kotlin"
        named("jvmMain") { kotlin.srcDir(jvmShared) }
        named("androidMain") { kotlin.srcDir(jvmShared) }
    }
}

android {
    defaults("dev.shibasis.reaktor.crypto")
}
