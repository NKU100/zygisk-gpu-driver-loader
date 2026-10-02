package io.github.nku100.webui.data

import io.github.nku100.webui.ModuleInfo
import io.github.nku100.webui.platform.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DriverZipIntegrationTest {
    @Test
    fun importsOriginalArchivesThroughProductionPublisher() = runBlocking {
        val supplied = System.getenv("GPU_DRIVER_TEST_ZIPS")
        assumeTrue("Provide local driver ZIP paths to run the opt-in integration test", !supplied.isNullOrBlank())
        val root = "/data/adb/${ModuleInfo.MODULE_ID}/drivers"
        for (path in supplied!!.split(File.pathSeparator)) {
            val archive = File(path)
            val sandbox = Files.createTempDirectory("gpu-zip-import-").toFile()
            val target = File(sandbox, "drivers")
            val execute: RootCommand = { command ->
                val process = ProcessBuilder("/bin/sh", "-c", command.replace(root, target.absolutePath))
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                ShellResult(process.waitFor(), output, "")
            }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().map { DriverFileInfo(it.name, !it.isDirectory) }.toList()
                val metadata = DriverRepository.parseMeta(zip.getInputStream(zip.getEntry("meta.json")).bufferedReader().readText())
                val hash = Sha256.digestHex(archive.readBytes())
                val result = DriverRepository.publish(hash, entries, metadata, 1L, execute) { name, append ->
                    zip.getInputStream(zip.getEntry(name)).use { input ->
                        DriverRepository.transfer(input::read, append)
                    }
                }
                val accepted = assertIs<DriverImportResult.Accepted>(result)
                assertEquals("arm64-v8a", accepted.driver.abi)
                val published = File(target, accepted.driver.driverId)
                for (entry in entries.filter { it.path == "meta.json" || it.path.endsWith(".so") }) {
                    assertContentEquals(zip.getInputStream(zip.getEntry(entry.path)).use { it.readBytes() },
                        File(published, entry.path).readBytes())
                }
                assertEquals(accepted.driver.driverId, DriverRepository.readIndex(execute).single().driverId)
                println("Verified original ZIP ${archive.name}: ${published.absolutePath}")
            }
            if (System.getenv("GPU_DRIVER_KEEP_TEST_OUTPUT") != "true") sandbox.deleteRecursively()
        }
    }
}
