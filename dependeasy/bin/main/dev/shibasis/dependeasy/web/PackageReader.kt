package dev.shibasis.dependeasy.web
//
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.json.JsonElement
//import kotlinx.serialization.json.jsonObject
//import org.gradle.api.Project
//import org.gradle.api.tasks.Exec
//
//import org.gradle.kotlin.dsl.register
//import org.gradle.kotlin.dsl.support.uppercaseFirstChar
//import java.io.File
//
//
//fun scripts(packageJson: File): List<String>? {
//    val text = packageJson.readText()
//    val json = Json.decodeFromString<JsonElement>(text)
//    return json.jsonObject["scripts"]?.jsonObject?.entries?.map { it.key }
//}
//
//fun Project.buildTasksFromScripts(packageJson: File) {
//    if (!packageJson.exists()) return
//    val scripts = scripts(packageJson) ?: return
//    scripts.forEach { scriptName ->
//            tasks.register<Exec>("script" + scriptName.replace(Regex("""[/\\:<>"?*|]"""), "-").uppercaseFirstChar()) {
//                group = "package.json"
//                commandLine("zsh", "-c", "npm run $scriptName")
//            }
//        }
//}
//
