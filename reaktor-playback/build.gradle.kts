import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

// Audio playback the OS treats as a player: Media3 with a MediaSessionService on Android, AVPlayer
// with now-playing info and remote commands on Apple. A module of its own rather than a package in
// reaktor-media, because each side would cost the other something it cannot defend: reaktor-media
// merges RECORD_AUDIO and CameraX into its consumers, and this module merges a foreground media
// service, its permissions and Media3. See plans/hearth.md §4.2.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            commonCoroutines()
            commonSerialization()
        }
    }
    droid {
        dependencies {
            api("androidx.media3:media3-exoplayer:1.11.1")
            api("androidx.media3:media3-session:1.11.1")
        }
    }
    // Interface plus slot: the desktop and the browser have no player yet, and that is a normal
    // state rather than a missing actual — the slot simply stays empty there.
    darwin {}
    web {}
    server {}

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${dev.shibasis.dependeasy.Version.Coroutines}")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.playback")
}
