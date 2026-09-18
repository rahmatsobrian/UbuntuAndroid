package dev.ubuntu4a.core.proot

import dev.ubuntu4a.core.data.model.DistroInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class AptPackage(val name: String, val version: String, val description: String)

/** Thin typed wrapper over `apt-get` / `dpkg-query` executed inside proot. */
class AptRunner(private val runner: CommandRunner) {

    fun run(inst: DistroInstance, args: List<String>): Flow<AptEvent> = callbackFlow {
        val result = runner.execStreaming(
            inst,
            "DEBIAN_FRONTEND=noninteractive apt-get ${args.joinToString(" ")} " +
                "-o Dpkg::Options::=--force-confnew",
        ) { line ->
            trySend(AptEvent.Line(line))
        }
        if (result.success) trySend(AptEvent.Done) else trySend(AptEvent.Fail(result.exitCode))
        close()
    }

    suspend fun update(inst: DistroInstance): ExecResult =
        runner.exec(inst, "apt-get update")

    suspend fun install(inst: DistroInstance, packages: List<String>): ExecResult =
        runner.exec(
            inst,
            "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                "-o Dpkg::Options::=--force-confnew ${packages.joinToString(" ")}",
        )

    suspend fun remove(inst: DistroInstance, packages: List<String>): ExecResult =
        runner.exec(inst, "apt-get remove -y ${packages.joinToString(" ")}")

    suspend fun search(inst: DistroInstance, query: String): List<AptPackage> {
        val safe = query.replace("'", "'\\''")
        val names = runner.exec(inst, "apt-cache search --no-full --names-only '$safe'")
            .output.lines().mapNotNull {
                Regex("^([^/\\s]+)/\\S+\\s").find(it)?.groupValues?.get(1)
            }.distinct().take(80)
        if (names.isEmpty()) return emptyList()
        val show = runner.exec(inst, "apt-cache show ${names.joinToString(" ")}").output
        return parseParagraphs(show)
    }

    suspend fun installedPackages(inst: DistroInstance): Set<String> {
        val out = runner.exec(
            inst,
            "dpkg-query -W -f='\${Package} \${db:Status-Abbrev} \${Version}\\n' 2>/dev/null",
        ).output
        return out.lines().filter { it.contains("ii ") }.mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }.toSet()
    }

    private fun parseParagraphs(text: String): List<AptPackage> {
        val result = mutableListOf<AptPackage>()
        for (block in text.split("\n\n")) {
            var name = ""
            var version = ""
            var desc = ""
            for (line in block.lines()) {
                when {
                    line.startsWith("Package:") -> name = line.substring(8).trim()
                    line.startsWith("Version:") -> if (version.isEmpty()) version = line.substring(8).trim()
                    line.startsWith("Description:") -> desc = line.substring(12).trim()
                }
            }
            if (name.isNotEmpty()) result += AptPackage(name, version, desc)
        }
        return result
    }
}

sealed interface AptEvent {
    data class Line(val text: String) : AptEvent
    data object Done : AptEvent
    data class Fail(val code: Int) : AptEvent
}
