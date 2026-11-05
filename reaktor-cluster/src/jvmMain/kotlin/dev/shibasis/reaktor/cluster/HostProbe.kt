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
            printf '\nREAKTOR_PROCESSES\n'
            LC_ALL=C /bin/ps -A -o pid=,pcpu=,rss=,comm= -r || printf 'REAKTOR_PROCESS_ERROR\n'
        """.trimIndent()

        fun parse(id: String, ssh: String?, roles: List<String>, text: String, at: Long): Host {
            val measurements = text.substringBefore("REAKTOR_PROCESSES\n")
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
            val marker = "REAKTOR_PROCESSES\n"
            val start = text.indexOf(marker)
            if (start < 0) return HostProcesses(at, emptyList(), "Unknown", "Process observation unavailable")
            val lines = text.substring(start + marker.length).lineSequence().filter { it.isNotBlank() }.toList()
            val rows = lines.mapNotNull { line ->
                val match = Regex("^\\s*(\\d+)\\s+([\\d.]+)\\s+(\\d+)\\s+(.+)$").matchEntire(line) ?: return@mapNotNull null
                val pid = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val cpu = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                val memory = match.groupValues[3].toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1024 }?.times(1024)
                val name = match.groupValues[4].trim().substringAfterLast('/').take(128)
                HostProcess(pid, name, cpu, memory)
            }.sortedByDescending { it.cpuPercent ?: 0.0 }
            val problem = when {
                text.length >= 65_536 -> "Process capture limit reached"
                rows.size < lines.size -> "Some process measurements are unavailable"
                rows.size > 64 -> "Showing the 64 busiest of ${rows.size} observed processes"
                else -> null
            }
            return HostProcesses(at, rows.take(64), if (problem == null) "Live" else "Partial", problem)
        }
    }
}
