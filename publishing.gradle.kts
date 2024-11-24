import java.time.LocalDate

group = "dev.shibasis"
version = LocalDate.now().run { "$year.$monthValue.$dayOfMonth" }
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

    publications.create(name.replace("-", ""), MavenPublication::class.java).apply {
        groupId = "dev.shibasis"
        artifactId = name
        version = project.version.toString()

        if (project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform"))
            from(project.components["kotlin"])
        else if (project.plugins.hasPlugin("java"))
            from(project.components["java"])
    }
}