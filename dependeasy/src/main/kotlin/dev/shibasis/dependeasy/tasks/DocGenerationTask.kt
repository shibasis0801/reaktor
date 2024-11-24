package dev.shibasis.dependeasy.tasks

import org.gradle.api.Task
import org.gradle.api.tasks.Copy

fun Copy.generateDocumentation() {
    group = "reaktor"
    dependsOn("dokkaHtml")
    from("${project.buildDir}/dokka/html")
    into("docs/${project.name}")
}