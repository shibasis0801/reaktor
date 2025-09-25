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

        maven {
            name = "MavenCentral"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("MAVEN_USERNAME") ?: ""
                password = System.getenv("MAVEN_TOKEN") ?: ""
            }
        }
    }
}
//
//tasks.withType<PublishToMavenRepository>().configureEach {
//    onlyIf {
//        when (repository.name) {
//            "GitHubPackages" -> isSnapshot
//            "MavenCentral"   -> !isSnapshot
//            else             -> true
//        }
//    }
//}