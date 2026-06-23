package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.io.File

/** `reaktor new <name>` — scaffold a minimal reaktor app (a package.json with a `reaktor` key). */
class New : CliktCommand("new") {
    private val env by requireObject<ReaktorEnv>()
    private val name by argument(help = "the new project directory name")
    private val template by option("--template", "-t", help = "basic | web | server").default("basic")
    override fun run() {
        val dir = File(System.getProperty("user.dir"), name)
        if (dir.exists()) throw UsageError("'$name' already exists.")
        dir.mkdirs()
        File(dir, "package.json").writeText(
            """
            {
              "name": "$name",
              "private": true,
              "reaktor": {
                "name": "$name",
                "targets": {
                  "web": { "runtime": "js-web", "dev": "dev", "deploy": "deploy" }
                }
              },
              "scripts": {
                "dev": "echo \"wire your dev server\"",
                "deploy": "echo \"wire your deploy\""
              }
            }
            """.trimIndent() + "\n",
        )
        File(dir, "README.md").writeText("# $name\n\nA reaktor app (template: $template).\nRun `reaktor tasks` in this directory.\n")
        env.terminal.println(green("✓") + " created " + bold(name) + dim("  (template: $template)"))
        env.terminal.println("  " + dim("cd $name && reaktor tasks"))
    }
}

/** `reaktor add <module>` — advisory: how to add a reaktor module (does not rewrite package.json). */
class Add : CliktCommand("add") {
    private val env by requireObject<ReaktorEnv>()
    private val module by argument(help = "auth | db | media | notification | work | …")
    override fun run() {
        val p = env.requireProject()
        env.terminal.println(bold("add reaktor-$module"))
        env.terminal.println("  Gradle:  " + dim("implementation(\"dev.shibasis.reaktor:reaktor-$module:<version>\")"))
        env.terminal.println("  Then add " + dim("\"$module\"") + " to the reaktor key's \"modules\" list.")
        if (module in p.modules) env.terminal.println(green("  already declared in reaktor.modules"))
    }
}
