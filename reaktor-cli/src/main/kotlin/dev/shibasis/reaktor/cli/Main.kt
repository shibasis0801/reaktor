package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlin.system.exitProcess

/**
 * The reaktor control CLI — one command line for every reaktor app.
 *
 * C0/C1 + script-family ergonomics (see /docs/reaktor-cli): a Clikt command tree over a project
 * model discovered from the current project's existing files — the `reaktor` key in its
 * package.json plus its gradle settings — executed by [ProcessRunner], a stand-in for
 * reaktor-cloud's ProcessToolRunner that a later phase swaps in. Built-in commands are joined at
 * startup by one dynamic subcommand per colon-namespaced script family (fastlane, maestro, karate, k6, ...).
 */
class Reaktor(env: ReaktorEnv) : CliktCommand() {
    private val sharedEnv by findOrSetObject { env }

    init {
        versionOption("0.1.0")
    }

    override fun run() {
        // Reading the delegate populates the shared context object for every subcommand.
        sharedEnv
    }
}

fun main(args: Array<String>) {
    val env = ReaktorEnv.create()
    if (args.isEmpty()) {
        // Bare `reaktor` prints the full overview: the project model + the command surface.
        printOverview(env)
        return
    }
    val commands = listOf(
        Tasks(), Run(), Logs(), Dev(), Build(), Deploy(), Test(), Gradle(), Docs(),
        New(), Add(), Explain(), Graph(), Engine(), Infra(), Dagger(),
        Db().subcommands(DbConsole(), DbStatus(), DbMigrate()),
        Cloud().subcommands(CloudDagger(), CloudPulumi(), CloudInventory()),
        Auth().subcommands(AuthLogin(), AuthToken(), AuthWhoami()),
        Doctor(), Install(), Self().subcommands(Update(), Uninstall()),
    ) +
        scriptFamilies(env.project) +   // fastlane / maestro / karate / k6 / perf ... from the project's scripts
        shortcutCommands(env.project)   // targets, services, stores, gradle modules, direct ops scripts
    if (!args[0].startsWith("-") && args[0] !in knownTopLevelNames(env.project)) {
        printUnknownTopLevel(env, args[0])
        exitProcess(1)
    }
    Reaktor(env).subcommands(*commands.toTypedArray()).main(args)
}
