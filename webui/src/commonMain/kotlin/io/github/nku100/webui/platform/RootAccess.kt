package io.github.nku100.webui.platform

enum class RootImplementation { MAGISK, KERNEL_SU, APATCH, UNKNOWN }

data class RootEnvironment(
    val available: Boolean = false,
    val implementation: RootImplementation = RootImplementation.UNKNOWN,
    val version: String = "",
)

object RootAccess {
    suspend fun environment(execute: suspend (String) -> ShellResult = PlatformBridge::exec): RootEnvironment {
        val result = execute("""
            [ "${'$'}(id -u)" = 0 ] || exit 1
            printf 'ROOT_UID=0\n'
            su -v 2>/dev/null || true
        """.trimIndent())
        return parseEnvironment(result)
    }

    internal fun parseEnvironment(result: ShellResult): RootEnvironment {
        if (result.errno != 0 || "ROOT_UID=0" !in result.stdout.lineSequence()) return RootEnvironment()
        val version = result.stdout.lineSequence().filter { it != "ROOT_UID=0" }.joinToString(" ").trim().take(160)
        val implementation = when {
            version.contains("MAGISKSU", ignoreCase = true) -> RootImplementation.MAGISK
            version.contains("KernelSU", ignoreCase = true) -> RootImplementation.KERNEL_SU
            version.contains("APatch", ignoreCase = true) -> RootImplementation.APATCH
            else -> RootImplementation.UNKNOWN
        }
        return RootEnvironment(true, implementation, version)
    }

    suspend fun packages(
        userId: Int? = null,
        execute: suspend (String) -> ShellResult = PlatformBridge::exec,
    ): List<PackageInfo> {
        require(userId == null || userId >= 0)
        val user = userId?.toString() ?: "${'$'}(am get-current-user)"
        val result = execute("""
            [ "${'$'}(id -u)" = 0 ] || exit 1
            app_user=$user
            case "${'$'}app_user" in ''|*[!0-9]*) exit 1 ;; esac
            pm list packages --user "${'$'}app_user" || exit 1
            printf '__SYSTEM_PACKAGES__\n'
            pm list packages -s --user "${'$'}app_user" || exit 1
        """.trimIndent())
        return parsePackages(result)
    }

    internal fun parsePackages(result: ShellResult): List<PackageInfo> {
        check(result.errno == 0) { "Root application list request failed" }
        val lines = result.stdout.lines()
        val boundary = lines.indexOf("__SYSTEM_PACKAGES__")
        check(boundary >= 0) { "Incomplete root application list" }
        fun names(lines: List<String>) = lines.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        val all = names(lines.take(boundary))
        check(all.isNotEmpty()) { "Root application list is empty" }
        val system = names(lines.drop(boundary + 1))
        return all.sorted().map { PackageInfo(it, isSystemApp = it in system) }
    }
}
