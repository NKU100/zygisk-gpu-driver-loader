package io.github.nku100.webui.data

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.coroutines.startCoroutine

class DriverRepositoryTest {
    @Test
    fun parsesDirectoryProtocolWithUnicodeNames() {
        val path = "/storage/emulated/0/Drivers"
        val output = listOf(
            "DRIVER_IMPORT_V1\tOK\tLIST",
            "DIR\t${Base64.encode("转\u6362".encodeToByteArray())}",
            "ZIP\t${Base64.encode("Turnip 25.2.zip".encodeToByteArray())}",
        ).joinToString("\n", postfix = "\n")

        val directory = DriverRepository.parseDirectoryProtocol(path, output)

        assertEquals(path, directory.path)
        assertEquals(
            listOf(DriverPathEntry("转\u6362", true), DriverPathEntry("Turnip 25.2.zip", false)),
            directory.entries,
        )
    }

    @Test
    fun rejectsMalformedOrTruncatedHelperOutput() {
        for (output in listOf(
            "",
            "DRIVER_IMPORT_V2\tOK\tLIST\n",
            "DRIVER_IMPORT_V1\tOK\tLIST\nZIP\t%%%\n",
            "DRIVER_IMPORT_V1\tOK\tLIST\nDIR\t${Base64.encode("../escape".encodeToByteArray())}\n",
            "DRIVER_IMPORT_V1\tOK\tLIST\nDIR\t${Base64.encode("folder".encodeToByteArray())}",
        )) {
            assertFailsWith<DriverPathException> { DriverRepository.parseDirectoryProtocol("/storage/emulated/0", output) }
        }
    }

    @Test
    fun parsesPrepareProtocolAndRejectsTruncatedRecords() {
        val meta = "{\"name\":\"Turnip\",\"libraryName\":\"libvk.so\",\"abi\":\"arm64-v8a\"}"
        val hash = "a".repeat(64)
        val output = listOf(
            "DRIVER_IMPORT_V1\tOK\tPREPARE",
            "ARCHIVE\t$hash",
            "META\t${Base64.encode(meta.encodeToByteArray())}",
            "ENTRY\t${Base64.encode("meta.json".encodeToByteArray())}\t1\t0",
            "ENTRY\t${Base64.encode("libvk.so".encodeToByteArray())}\t1\t0",
            "FILE\t${Base64.encode("meta.json".encodeToByteArray())}\t${meta.encodeToByteArray().size}\t${"b".repeat(64)}",
            "FILE\t${Base64.encode("libvk.so".encodeToByteArray())}\t64\t${"c".repeat(64)}",
            "STAGE\t.stage-abc123",
        ).joinToString("\n", postfix = "\n")

        val prepared = DriverRepository.parsePreparedProtocol(output)

        assertEquals(hash, prepared.archiveSha256)
        assertEquals("Turnip", prepared.metaJson["name"])
        assertEquals(setOf("meta.json", "libvk.so"), prepared.fileHashes.keys)
        assertEquals(".stage-abc123", prepared.stageName)
        assertFailsWith<DriverStoreException> { DriverRepository.parsePreparedProtocol(output.dropLast(1)) }
    }

    @Test
    fun quotesHelperArgsContainingSpacesAndSingleQuotes() {
        val path = "/storage/emulated/0/Driver O'Brien.zip"
        var command = ""
        runSuspend {
            DriverRepository.listZipDirectory(path) {
                command = it
                io.github.nku100.webui.platform.ShellResult(0, "DRIVER_IMPORT_V1\tOK\tLIST\n", "")
            }
        }

        assertContains(command, "'/storage/emulated/0/Driver O'\\''Brien.zip'")
    }

    @Test
    fun timedOutHelperRemovesOnlyNonceOwnedTemporaryFiles() {
        val commands = mutableListOf<String>()
        runSuspend {
            DriverRepository.importDriverZip("/storage/emulated/0/Turnip.zip", 1L) { command ->
                commands += command
                if (command.contains("DriverPathHelper")) {
                    io.github.nku100.webui.platform.ShellResult(-1, "", "timeout")
                } else {
                    io.github.nku100.webui.platform.ShellResult(0, "", "")
                }
            }
        }

        val cleanup = commands.filter { it.startsWith("if [ -e ") }
        assertEquals(2, cleanup.size)
        assertTrue(Regex("\\.stage-[0-9a-f]+").containsMatchIn(cleanup[1]))
        assertContains(cleanup[0], ".snapshot-")
        assertFalse(cleanup.any { it == "rm -rf '/data/adb/${io.github.nku100.webui.ModuleInfo.MODULE_ID}/drivers'" })
        assertEquals(1, commands.count { it.contains("DriverPathHelper") })
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digestHex("abc".encodeToByteArray()))
    }

    @Test
    fun binaryTransferReconstructsMoreThanOneChunkWithoutChangingSha256() {
        val source = ByteArray(RootFileTransfer.CHUNK_SIZE * 3 + 17) { (it * 37).toByte() }
        val output = mutableListOf<Byte>()
        val chunks = mutableListOf<String>()
        var offset = 0

        runSuspend {
            RootFileTransfer.transfer(
                read = { buffer ->
                    val count = minOf(buffer.size, source.size - offset)
                    if (count == 0) return@transfer -1
                    source.copyInto(buffer, 0, offset, offset + count)
                    offset += count
                    count
                },
                appendBase64 = { chunk ->
                    chunks += chunk
                    output += Base64.decode(chunk).toList()
                },
            )
        }

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { Base64.decode(it).size <= RootFileTransfer.CHUNK_SIZE })
        assertContentEquals(source, output.toByteArray())
        assertEquals(Sha256.digestHex(source), Sha256.digestHex(output.toByteArray()))
    }

    @Test
    fun shortReadsAreCombinedBeforeRootTransfer() {
        val source = ByteArray(RootFileTransfer.CHUNK_SIZE * 2 + 17) { (it * 37).toByte() }
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var digest = ""

        runSuspend {
            digest = RootFileTransfer.transfer(
                read = { buffer ->
                    if (offset == source.size) -1 else {
                        val count = minOf(137, buffer.size, source.size - offset)
                        source.copyInto(buffer, 0, offset, offset + count)
                        offset += count
                        count
                    }
                },
                appendBase64 = { chunks += Base64.decode(it) },
            )
        }

        assertEquals(3, chunks.size)
        assertEquals(17, chunks.last().size)
        assertContentEquals(source, chunks.flatMap { it.toList() }.toByteArray())
        assertEquals(Sha256.digestHex(source), digest)
    }

    @Test
    fun boundDriverCannotBeDeleted() {
        val config = ModuleConfig(packageSettings = mapOf("example.app" to PackageSettings(driverId = "driver-a")))
        assertFalse(DriverRepository.canDelete("driver-a", config))
        assertTrue(DriverRepository.canDelete("driver-b", config))
    }

    @Test
    fun rejectsDriverIdTooLongForOneDirectoryComponent() {
        val libraryName = "lib${"x".repeat(100)}.so"
        var result: DriverImportResult? = null
        runSuspend {
            result = DriverRepository.publishPrepared(
                DriverPreparedImport(
                    "a".repeat(64),
                    listOf(DriverFileInfo("meta.json", true), DriverFileInfo(libraryName, true)),
                    mapOf("libraryName" to libraryName, "abi" to "arm64-v8a"),
                    mapOf("meta.json" to "b".repeat(64), libraryName to "c".repeat(64)),
                    ".stage-1",
                ),
                0L,
                execute = { io.github.nku100.webui.platform.ShellResult(0, "", "") },
            )
        }
        assertEquals(DriverArchiveError.INVALID_ENTRY_PATH, assertIs<DriverImportResult.Rejected>(result).error)
    }

    private fun runSuspend(block: suspend () -> Unit) {
        block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) = result.getOrThrow()
        })
    }
}
