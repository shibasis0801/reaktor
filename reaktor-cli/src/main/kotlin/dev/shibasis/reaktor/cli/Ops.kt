package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import java.io.File

private val INFRA_NAMES = setOf("ssh", "radar", "memgraph", "clickhouse")
private val STORE_CONSOLES = setOf("memgraph", "clickhouse", "postgres")

/** `reaktor infra <name>` — run an infra/ops script (ssh, radar, memgraph, clickhouse, …). */
class Infra : CliktCommand("infra") {
    private val env by requireObject<ReaktorEnv>()
    private val name by argument(help = "ssh | radar | memgraph | clickhouse").optional()
    override fun run() {
        val p = env.requireProject()
        val available = p.scripts.keys.filter { it in INFRA_NAMES }.sorted()
        val n = name
        if (n == null) {
            env.terminal.println(bold("infra") + dim(" — `reaktor infra <name>`:  ") + available.joinToString("  "))
            return
        }
        val command = p.directScriptCommand(n)
            ?: throw UsageError("No infra script '$n'. Available: ${available.joinToString(", ")}")
        runChecked(env, command)
    }
}

/** `reaktor db <console|status|migrate>` — the project's stores. */
class Db : CliktCommand("db") {
    override fun run() {}
}

class DbConsole : CliktCommand("console") {
    private val env by requireObject<ReaktorEnv>()
    private val store by argument(help = "memgraph | clickhouse | postgres").optional()
    override fun run() {
        val p = env.requireProject()
        val consoles = p.scripts.keys.filter { it in STORE_CONSOLES }.sorted()
        val s = store ?: throw UsageError("Which store? ${consoles.joinToString(", ")}")
        val command = p.storeConsoleCommand(s)
            ?: throw UsageError("No console script for '$s'. Available: ${consoles.joinToString(", ")}")
        runChecked(env, command)
    }
}

class DbStatus : CliktCommand("status") {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val p = env.requireProject()
        val stores = p.stores.ifEmpty { p.scripts.keys.filter { it in STORE_CONSOLES }.sorted() }
        env.terminal.println(bold("stores (${stores.size})"))
        if (stores.isEmpty()) env.terminal.println(dim("  none (add a \"stores\" list to the reaktor key)"))
        else stores.forEach { env.terminal.println("  • $it") }
    }
}

class DbMigrate : CliktCommand("migrate") {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "extra args for the migrate script").multiple()
    override fun run() {
        val p = env.requireProject()
        val script = p.scripts.keys.firstOrNull { it == "migrate" || it.contains("migrat", ignoreCase = true) }
            ?: throw UsageError("No migrate script in package.json. Add one, or use `reaktor run <script>`.")
        runChecked(env, p.directScriptCommand(script, args) ?: throw UsageError("No script '$script'."))
    }
}

/** `reaktor cloud <inventory|dagger|pulumi>` — drive the cloud tool CLIs. */
class Cloud : CliktCommand("cloud") {
    override fun run() {}
}

class CloudDagger : CliktCommand("dagger") {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "args for `dagger` (e.g. `functions`, `call pr`)").multiple()
    override fun run() {
        runDagger(env, args)
    }
}

class Dagger : CliktCommand("dagger") {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "args for `dagger` (e.g. `functions`, `call pr`)").multiple()
    override fun run() {
        runDagger(env, args)
    }
}

private fun runDagger(env: ReaktorEnv, args: List<String>) {
    val p = env.requireProject()
    val dir = p.cloud["dagger"]?.let { File(p.root, it) }?.takeIf { it.exists() } ?: p.root
    val argv = listOf("dagger") + args.ifEmpty { listOf("functions") }
    runChecked(env, ProjectCommand(argv.joinToString(" "), argv, dir))
}

class CloudPulumi : CliktCommand("pulumi") {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "args for `pulumi` (e.g. `preview`, `stack ls`)").multiple()
    override fun run() {
        val p = env.requireProject()
        val dir = p.cloud["pulumi"]?.let { File(p.root, it) }?.takeIf { it.exists() } ?: p.root
        val effectiveArgs = args.ifEmpty { listOf("stack", "ls") }
        requireExplicitPulumiStack(effectiveArgs)
        val argv = listOf("pulumi") + effectiveArgs
        runChecked(env, ProjectCommand(argv.joinToString(" "), argv, dir))
    }
}

fun requireExplicitPulumiStack(args: List<String>) {
    val stackScopedVerbs = setOf(
        "preview", "up", "refresh", "destroy", "import", "config", "cancel", "export",
        "state", "history", "output", "watch", "logs", "console", "about",
    )
    val normalized = args.map(String::lowercase)
    val stackSubcommand = normalized.indexOf("stack")
        .takeIf { it >= 0 }
        ?.let { normalized.getOrNull(it + 1) }
    val stackCommandIsScoped = stackSubcommand != null && stackSubcommand !in setOf("ls", "select", "init")
    if (normalized.none { it in stackScopedVerbs } && !stackCommandIsScoped) return
    val hasInlineStack = args.any { argument ->
        (argument.startsWith("--stack=") || argument.startsWith("-s=")) && argument.substringAfter('=').isNotBlank()
    }
    val stackFlag = args.indexOfFirst { it == "--stack" || it == "-s" }
    val hasSeparateStack = stackFlag >= 0 && args.getOrNull(stackFlag + 1)?.takeUnless { it.startsWith('-') }?.isNotBlank() == true
    if (!hasInlineStack && !hasSeparateStack) {
        throw UsageError(
            "Stack-scoped Pulumi commands require an explicit --stack <name> (or -s <name>); " +
                "ambient stack selection is not allowed.",
        )
    }
}

class CloudInventory : CliktCommand("inventory") {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val p = env.requireProject()
        env.terminal.println(bold("workers / services (${p.services.size})"))
        env.terminal.println(table {
            header { row("service", "workspace") }
            body { p.workspaces.forEach { row(it.substringAfterLast('/'), it) } }
        })
        env.terminal.println(dim("cloudflare account:"))
        val argv = listOf("npx", "--no-install", "wrangler", "whoami")
        runChecked(env, ProjectCommand(argv.joinToString(" "), argv, p.root))
    }
}
