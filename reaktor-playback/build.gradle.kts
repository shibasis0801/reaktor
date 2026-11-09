import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Audio playback the OS treats as a player: Media3 with a MediaSessionService on Android, AVPlayer
// with now-playing info and remote commands on Apple. A module of its own rather than a package in
// reaktor-media, because each side would cost the other something it cannot defend: reaktor-media
// merges RECORD_AUDIO and CameraX into its consumers, and this module merges a foreground media
// service, its permissions and Media3. See plans/hearth.md §4.2. The desktop and the browser have
// no player yet: the slot simply stays empty there.
library(namespace = "dev.shibasis.reaktor.playback", targets = Targets.All) {
    dependencies {
        common {
            api(projects.reaktorCore)
            api(Versions.Kotlin.CoroutinesCore)
            api(Versions.Kotlin.SerializationJson)
        }
        android {
            api("androidx.media3:media3-exoplayer:1.11.1")
            api("androidx.media3:media3-session:1.11.1")
        }
    }
}
