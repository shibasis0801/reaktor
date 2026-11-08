package dev.shibasis.reaktor.cluster

import java.util.concurrent.TimeUnit

data class CommandResult(val exitCode: Int, val output: String)

fun interface ClusterCommands {
    fun run(command: List<String>, timeoutSeconds: Long): CommandResult
}

object SystemClusterCommands : ClusterCommands {
    override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drain = Thread.ofVirtual().start {
            runCatching { process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) { if (output.length < 65_536) output.append(buffer, 0, minOf(count, 65_536 - output.length)) }
                }
            } }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            process.inputStream.close()
            drain.join(1000)
            return CommandResult(124, "Probe timed out")
        }
        drain.join(1000)
        process.inputStream.close()
        return CommandResult(process.exitValue(), synchronized(output) { output.toString() })
    }
}

class HostProbe(private val commands: ClusterCommands = SystemClusterCommands, private val now: () -> Long = System::currentTimeMillis) {
    fun read(id: String, ssh: String? = null): Host {
        val roles = if (ssh == null) listOf("build", "interactive") else listOf("build", "device", "docker")
        val command = if (ssh == null) listOf("/bin/sh", "-c", probe) else {
            require(ssh.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")))
            listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=3", "--", ssh, probe)
        }
        return try {
            val result = commands.run(command, 8)
            if (result.exitCode != 0) Host(id, ssh, roles, "Unreachable", observedAt = now(), truth = "Failed", problem = "Capacity probe exited ${result.exitCode}")
            else parse(id, ssh, roles, result.output, now())
        } catch (_: Exception) { Host(id, ssh, roles, "Unreachable", observedAt = now(), truth = "Failed", problem = "Capacity probe unavailable") }
    }

    companion object {
        private val probe = """
            /usr/sbin/sysctl hw.ncpu hw.memsize kern.memorystatus_vm_pressure_level vm.swapusage vm.loadavg
            /usr/bin/vm_stat
            /usr/bin/memory_pressure -Q
            /usr/bin/top -l 2 -s 1 -n 0
            /bin/df -k /System/Volumes/Data
            printf '\nREAKTOR_ROLES\n'
            LC_ALL=C /bin/ps -ww -A -o pid=,pcpu=,rss=,args= | /usr/bin/awk '$Roles'
            printf '\nREAKTOR_PROCESSES\n'
            LC_ALL=C /bin/ps -A -o pid=,pcpu=,rss=,comm= -r || printf 'REAKTOR_PROCESS_ERROR\n'
        """.trimIndent()

        private const val Roles = """{ c = $4; sub(/.*\//, "", c); r = "" }
            c == "java" && /ai\.bestbuds\.reaktor\.MainKt --host/ { r = "hangar-host" }
            c == "java" && /ai\.bestbuds\.reaktor\.kernel\.KernelHostKt/ { r = "hangar-host" }
            c == "java" && r == "" && /ai\.bestbuds\.reaktor\.MainKt/ { r = "hangar-window" }
            c == "Hangar" { r = "hangar-window" }
            c == "java" && /ConductorCliKt workspace mcp/ { r = "mcp-relay" }
            c == "java" && /KotlinCompileDaemon/ { r = "kotlin-daemon" }
            c == "java" && /GradleDaemon/ { r = "gradle-daemon" }
            c == "java" && /GradleWrapperMain/ { r = "gradle-client" }
            c == "clangd" { r = "clangd" }
            c == "idb_companion" { r = "idb-companion" }
            c ~ /^node/ && /scip-index\.mjs/ { r = "typescript-indexer" }
            c ~ /^[Pp]ython/ && $5 ~ /reaktor-cluster\/tooling\// { t = $5; sub(/.*\//, "", t); r = "tooling:" t }
            r != "" { print $1, $2, $3, r, c }"""

        private const val RolesMarker = "REAKTOR_ROLES\n"
        private const val ProcessesMarker = "REAKTOR_PROCESSES\n"

        fun parse(id: String, ssh: String?, roles: List<String>, text: String, at: Long): Host {
            val measurements = text.substringBefore(RolesMarker).substringBefore(ProcessesMarker)
            fun number(pattern: String) = Regex(pattern).findAll(measurements).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
            val page = number("page size of (\\d+) bytes")
            val swap = Regex("used = ([\\d.]+)([MG])").find(measurements)
            val capacity = Capacity(
                cores = number("hw.ncpu: (\\d+)")?.toInt(), memoryBytes = number("hw.memsize: (\\d+)")?.toLong(),
                cpuIdlePercent = number("([\\d.]+)% idle"), memoryFreePercent = number("System-wide memory free percentage: (\\d+)%"),
                pressure = number("kern.memorystatus_vm_pressure_level: (\\d+)")?.toInt(),
                compressedBytes = number("Pages occupied by compressor:\\s+(\\d+)")?.let { pages -> page?.let { (pages * it).toLong() } },
                swapBytes = swap?.let { (it.groupValues[1].toDouble() * if (it.groupValues[2] == "G") 1_073_741_824 else 1_048_576).toLong() },
                diskFreeBytes = measurements.lineSequence().lastOrNull { it.trim().startsWith("/dev/") }?.trim()?.split(Regex("\\s+"))?.getOrNull(3)?.toLongOrNull()?.times(1024),
                load = number("vm.loadavg: \\{ ([\\d.]+)"),
            )
            val complete = listOf(capacity.cores, capacity.memoryBytes, capacity.cpuIdlePercent, capacity.memoryFreePercent,
                capacity.pressure, capacity.compressedBytes, capacity.swapBytes, capacity.diskFreeBytes, capacity.load).all { it != null }
            return Host(id, ssh, roles, "Reachable", capacity, at, if (complete) "Live" else "Partial",
                if (complete) null else "Some capacity measurements are unavailable", parseProcesses(text, at))
        }

        internal fun parseProcesses(text: String, at: Long): HostProcesses {
            val start = text.indexOf(ProcessesMarker)
            if (start < 0) return HostProcesses(at, emptyList(), "Unknown", "Process observation unavailable")
            val lines = text.substring(start + ProcessesMarker.length).lineSequence().filter { it.isNotBlank() }.toList()
            val rows = lines.mapNotNull { line -> ProcessRow.matchEntire(line)?.let { process(it, it.groupValues[4]) } }.sortedByDescending { it.cpuPercent ?: 0.0 }
            val roles = text.substringAfter(RolesMarker, "").substringBefore(ProcessesMarker).lineSequence()
                .mapNotNull { line -> RoleRow.matchEntire(line)?.let { process(it, it.groupValues[5], it.groupValues[4]) } }
                .associateBy { it.pid }
            val problem = when {
                text.length >= 65_536 -> "Process capture limit reached"
                rows.size < lines.size -> "Some process measurements are unavailable"
                rows.size > 64 -> "Showing the 64 busiest of ${rows.size} observed processes"
                else -> null
            }
            val busiest = rows.take(64).map { row -> roles[row.pid]?.let { row.copy(role = it.role) } ?: row }
            return HostProcesses(at, busiest + roles.values.filter { role -> busiest.none { it.pid == role.pid } },
                if (problem == null) "Live" else "Partial", problem)
        }

        private val ProcessRow = Regex("^\\s*(\\d+)\\s+([\\d.]+)\\s+(\\d+)\\s+(.+)$")
        private val RoleRow = Regex("^\\s*(\\d+)\\s+([\\d.]+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s*$")

        private fun process(match: MatchResult, name: String, role: String? = null): HostProcess? {
            val pid = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val cpu = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val memory = match.groupValues[3].toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1024 }?.times(1024)
            return HostProcess(pid, name.trim().substringAfterLast('/').take(128), cpu, memory, role)
        }
    }
}
