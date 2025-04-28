import java.time.LocalDate

group = "dev.shibasis"
version = LocalDate.now().run { "$year.$monthValue.$dayOfMonth-SNAPSHOT" }
extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/shibasis0801/flatInvoker")
            credentials {
                username = System.getenv("USERNAME") ?: ""
                password = System.getenv("TOKEN") ?: ""
            }
        }
    }
}