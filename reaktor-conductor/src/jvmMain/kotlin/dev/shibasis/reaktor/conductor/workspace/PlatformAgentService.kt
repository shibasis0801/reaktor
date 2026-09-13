package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Per-user supervisors. No administrator rights, credential copying or system-wide installation. */
internal object PlatformAgentService {
    fun start(root: File, command: List<String>, directory: Path) {
        val label = AgentBackgroundService.label(root)
        if (System.getProperty("os.name").lowercase().contains("win")) {
            val spec = directory.resolve("scheduled-task.ps1")
            val exists = run(powershell("if (Get-ScheduledTask -TaskName ${ps(label)} -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }")).first == 0
            if (exists) {
                require(Files.exists(spec)) { "A task with this name is not owned by Reaktor" }
                val expected = directory.resolve("scheduled-task.xml")
                require(Files.exists(expected) && run(powershell("Export-ScheduledTask -TaskName ${ps(label)}")).second.trim() == Files.readString(expected).trim()) {
                    "Scheduled task changed outside Reaktor; inspect it before replacing"
                }
                runChecked(powershell("Start-ScheduledTask -TaskName ${ps(label)}"))
            } else {
                val content = windowsConfiguration(label, command, root, directory)
                atomicWrite(spec, content)
                runChecked(powershell(content))
                atomicWrite(directory.resolve("scheduled-task.xml"), runChecked(powershell("Export-ScheduledTask -TaskName ${ps(label)}")))
            }
        } else {
            require(System.getProperty("os.name").lowercase().contains("linux")) { "No qualified supervisor configuration for this operating system" }
            val location = Path.of(System.getenv("XDG_CONFIG_HOME") ?: Path.of(System.getProperty("user.home"), ".config").toString(), "systemd", "user")
            Files.createDirectories(location)
            val file = location.resolve("$label.service")
            val owned = directory.resolve("systemd.service")
            if (Files.exists(file)) require(Files.exists(owned) && Files.readString(file) == Files.readString(owned)) { "Service configuration changed outside Reaktor" }
            val content = systemdConfiguration(command, root, directory)
            atomicWrite(file, content); atomicWrite(owned, content)
            runChecked(listOf("systemctl", "--user", "daemon-reload"))
            runChecked(listOf("systemctl", "--user", "enable", "--now", "$label.service"))
        }
    }
    fun stop(root: File): String {
        val label = AgentBackgroundService.label(root)
        if (System.getProperty("os.name").lowercase().contains("win"))
            runChecked(powershell("Stop-ScheduledTask -TaskName ${ps(label)}"))
        else runChecked(listOf("systemctl", "--user", "stop", "$label.service"))
        return "Background owner stopped. Saved tasks remain available at the next start or login."
    }
    fun removeForUpgrade(root: File) {
        if (System.getProperty("os.name").lowercase().contains("win"))
            runChecked(powershell("Unregister-ScheduledTask -TaskName ${ps(AgentBackgroundService.label(root))} -Confirm:\$false"))
    }
    internal fun systemdConfiguration(command: List<String>, root: File, directory: Path): String {
        require(command.isNotEmpty() && command.none { '\n' in it || '\r' in it })
        fun quote(text: String, commandArgument: Boolean = false) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").let { if (commandArgument) it.replace("$", "$$") else it } + "\""
        return """
            [Unit]
            Description=Reaktor workspace agents
            StartLimitIntervalSec=0
            [Service]
            Type=exec
            ExecStart=${command.joinToString(" ") { quote(it, true) }}
            WorkingDirectory=${quote(root.canonicalPath)}
            Environment=REAKTOR_AGENT_SUPERVISOR=systemd
            Environment=${quote("PATH=" + agentServicePath())}
            Restart=always
            RestartSec=10
            KillMode=control-group
            TimeoutStopSec=30
            UMask=0077
            StandardOutput=${quote("append:" + directory.resolve("service.log"))}
            StandardError=inherit
            [Install]
            WantedBy=default.target
        """.trimIndent() + "\n"
    }
    internal fun windowsConfiguration(label: String, command: List<String>, root: File, directory: Path): String {
        val launch = directory.resolve("run-service.ps1")
        val body = "\$env:REAKTOR_AGENT_SUPERVISOR='task-scheduler'\n\$env:PATH=${ps(agentServicePath())}\n& " +
            command.joinToString(" ", transform = ::ps) + " *>> ${ps(directory.resolve("service.log").toString())}\nexit \$LASTEXITCODE\n"
        // The script path is owned; argv is literal PowerShell data, never an interpolated shell command.
        atomicWrite(launch, body)
        val args = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"${launch.toString().replace("\"", "\\\"")}\""
        return """
            ${'$'}ErrorActionPreference='Stop'
            ${'$'}action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ${ps(args)} -WorkingDirectory ${ps(root.canonicalPath)}
            ${'$'}identity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
            ${'$'}trigger=New-ScheduledTaskTrigger -AtLogOn -User ${'$'}identity
            ${'$'}principal=New-ScheduledTaskPrincipal -UserId ${'$'}identity -LogonType Interactive -RunLevel Limited
            ${'$'}settings=New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
            Register-ScheduledTask -TaskName ${ps(label)} -Action ${'$'}action -Trigger ${'$'}trigger -Principal ${'$'}principal -Settings ${'$'}settings | Out-Null
            Start-ScheduledTask -TaskName ${ps(label)}
        """.trimIndent()
    }
    private fun ps(text: String) = "'" + text.replace("'", "''") + "'"
    private fun powershell(script: String) = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
        Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
    private fun runChecked(command: List<String>): String = run(command).let { check(it.first == 0) { it.second.take(1500) }; it.second }
    private fun run(command: List<String>): Pair<Int, String> {
        val log = Files.createTempFile("reaktor-supervisor-", ".log")
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Service manager timed out") }
            process.exitValue() to Files.readString(log).take(100000)
        } finally { Files.deleteIfExists(log) }
    }
}
