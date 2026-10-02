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
    fun failedRootWriteRemovesStageWithoutChangingIndex() = runBlocking {
        val sandbox = Files.createTempDirectory("gpu-failed-import-").toFile()
        val target = File(sandbox, "drivers").apply { mkdir() }
        val index = File(target, "index.json").apply { writeText("[]") }
        val root = "/data/adb/${ModuleInfo.MODULE_ID}/drivers"
        try {
            val execute: RootCommand = { command ->
                val injected = if (command.contains("base64 -d") && command.contains("libtest.so"))
                    "$command; exit 9" else command
                val process = ProcessBuilder("/bin/sh", "-c", injected.replace(root, target.absolutePath))
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                ShellResult(process.waitFor(), output, "")
            }
            val result = DriverRepository.publish("b".repeat(64),
                listOf(DriverFileInfo("meta.json", true), DriverFileInfo("libtest.so", true)),
                mapOf("libraryName" to "libtest.so", "abi" to "arm64-v8a"), 1L, execute) { name, append ->
                val bytes = if (name == "meta.json") "{}".encodeToByteArray() else ByteArray(12288)
                append(kotlin.io.encoding.Base64.encode(bytes))
            }
            assertEquals(DriverArchiveError.STORAGE_ERROR, assertIs<DriverImportResult.Rejected>(result).error)
            assertEquals("[]", index.readText())
            assertEquals(listOf("index.json"), target.listFiles()!!.map { it.name })
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun batchesRootWritesWithoutChangingPublishedBytes() = runBlocking {
        val sandbox = Files.createTempDirectory("gpu-batched-import-").toFile()
        val target = File(sandbox, "drivers")
        val root = "/data/adb/${ModuleInfo.MODULE_ID}/drivers"
        val library = ByteArray(12 * 12288 + 17) { (it * 37).toByte() }
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1).copyInto(library)
        library[16] = 3; library[17] = 0
        library[18] = 183.toByte(); library[19] = 0
        byteArrayOf(1, 0, 0, 0).copyInto(library, 20)
        library[52] = 64; library[53] = 0
        val meta = "{\"libraryName\":\"libtest.so\",\"abi\":\"arm64-v8a\"}".encodeToByteArray()
        var libraryWrites = 0
        try {
            val execute: RootCommand = { command ->
                if (command.contains("base64 -d") && command.contains("libtest.so")) libraryWrites++
                val process = ProcessBuilder("/bin/sh", "-c", command.replace(root, target.absolutePath))
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                ShellResult(process.waitFor(), output, "")
            }
            val result = DriverRepository.publish("a".repeat(64),
                listOf(DriverFileInfo("meta.json", true), DriverFileInfo("libtest.so", true)),
                DriverRepository.parseMeta(meta.decodeToString()), 1L, execute) { name, append ->
                val bytes = if (name == "meta.json") meta else library
                var offset = 0
                DriverRepository.transfer({ buffer ->
                    if (offset == bytes.size) -1 else {
                        val count = minOf(buffer.size, bytes.size - offset)
                        bytes.copyInto(buffer, 0, offset, offset + count)
                        offset += count
                        count
                    }
                }, append)
            }
            val accepted = assertIs<DriverImportResult.Accepted>(result)
            assertContentEquals(library, File(target, "${accepted.driver.driverId}/libtest.so").readBytes())
            assertEquals(5, libraryWrites)
        } finally {
            sandbox.deleteRecursively()
        }
    }

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
