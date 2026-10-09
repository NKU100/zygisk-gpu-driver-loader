package io.github.nku100.webui.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverDownloadShellTest {
    @Test
    fun launcherReturnsBeforeDownloaderExitsAndCancelStopsChild() {
        withDownload { paths ->
            val runner = shell(paths.start(), paths).trim()
            assertTrue(runner.matches(Regex("[0-9]+")))
            eventually { Files.exists(paths.pid) }
            val child = Files.readString(paths.pid).trim()
            assertTrue(alive(child))

            shell(paths.cancel("0"), paths)

            eventually { !alive(child) }
            assertFalse(Files.exists(paths.pid))
            assertFalse(Files.exists(Path.of("${paths.pid}.runner")))
            assertFalse(Files.exists(paths.status))
        }
    }

    @Test
    fun cancelBeforeChildStartsDoesNotLeaveDownloaderOrStatus() {
        withDownload { paths ->
            val delayedStart = paths.start().replace("if command -v curl", "sleep 1; if command -v curl")
            val runner = shell(delayedStart, paths).trim()
            shell(paths.cancel("0"), paths)

            eventually { !alive(runner) }
            assertFalse(Files.exists(paths.pid))
            assertFalse(Files.exists(paths.status))
            assertFalse(Files.exists(paths.download))
        }
    }

    @Test
    fun downloaderCannotWriteAnUnboundedResponse() {
        withDownload { paths ->
            Files.writeString(paths.bin.resolve("curl"), """
                #!/bin/sh
                while [ "${'$'}1" != --output ]; do shift; done
                output=${'$'}2
                dd if=/dev/zero of="${'$'}output" bs=4096 count=2 2>/dev/null
                code=${'$'}?
                wc -c < "${'$'}output" > "${'$'}output.size"
                exit "${'$'}code"
            """.trimIndent() + "\n")
            shell(paths.start(), paths)
            eventually { Files.exists(paths.status) }

            assertTrue(Files.readString(paths.status) == "FAILED")
            val written = Files.readString(Path.of("${paths.partial}.size")).trim().toLong()
            assertTrue(written in 1..1024, "Downloader wrote $written bytes past the OS limit")
            assertFalse(Files.exists(paths.download))
        }
    }

    private fun withDownload(block: (DownloadPaths) -> Unit) {
        val root = Files.createTempDirectory("driver-download-shell-")
        val paths = DownloadPaths(root)
        Files.createDirectory(paths.bin)
        val probe = root.resolve("limit-probe")
        val unitProbe = ProcessBuilder("sh", "-c", "ulimit -c 0; ulimit -f 1; dd if=/dev/zero of='$probe' bs=4096 count=1 2>/dev/null")
            .redirectErrorStream(true).start()
        assertTrue(unitProbe.waitFor(3, TimeUnit.SECONDS))
        val unit = Files.size(probe)
        Files.writeString(paths.limits, "Max file size $unit $unit bytes\n")
        val curl = paths.bin.resolve("curl")
        Files.writeString(curl, "#!/bin/sh\nexec sleep 30\n")
        assertTrue(curl.toFile().setExecutable(true))
        try {
            block(paths)
        } finally {
            shell(paths.cancel("0"), paths)
            root.toFile().deleteRecursively()
        }
    }

    private fun shell(command: String, paths: DownloadPaths): String {
        val builder = ProcessBuilder("sh", "-c", command.replace("/proc/self/limits", "'${paths.limits}'")).redirectErrorStream(true)
        builder.environment()["PATH"] = "${paths.bin}:${builder.environment()["PATH"]}"
        val process = builder.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue(process.waitFor(3, TimeUnit.SECONDS), "Shell did not return promptly")
            val result = output.get(3, TimeUnit.SECONDS)
            assertTrue(process.exitValue() == 0, result)
            return result
        } finally {
            process.destroyForcibly()
        }
    }

    private fun alive(pid: String): Boolean {
        if (!pid.matches(Regex("[0-9]+"))) return false
        return ProcessBuilder("sh", "-c", "kill -0 $pid 2>/dev/null").start().waitFor() == 0
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }

    private class DownloadPaths(root: Path) {
        val bin: Path = root.resolve("bin")
        val limits: Path = root.resolve("limits")
        val download: Path = root.resolve(".download-a1.zip")
        val partial: Path = root.resolve(".download-a1.zip.part")
        val pid: Path = root.resolve(".download-a1.pid")
        val status: Path = root.resolve(".download-a1.status")

        fun start(): String = DriverRepository.startDownloadCommand(
            DriverReleaseAsset("owner/repo", "driver.zip", "https://github.com/owner/repo/releases/download/v1/driver.zip", 1, 0),
            partial.toString(), download.toString(), pid.toString(), status.toString(),
        )

        fun cancel(runner: String): String = DriverRepository.cancelDownloadCommand(
            partial.toString(), download.toString(), pid.toString(), status.toString(), runner, true,
        )
    }
}
