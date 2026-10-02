package io.github.nku100.webui.data

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.coroutines.startCoroutine

class DriverRepositoryTest {
    @Test
    fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digestHex("abc".encodeToByteArray()))
    }

    @Test
    fun binaryTransferReconstructsMoreThanOneChunkWithoutChangingSha256() {
        val source = ByteArray(DriverRepository.CHUNK_SIZE * 3 + 17) { (it * 37).toByte() }
        val output = mutableListOf<Byte>()
        val chunks = mutableListOf<String>()
        var offset = 0

        runSuspend {
            DriverRepository.transfer(
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
        assertTrue(chunks.all { Base64.decode(it).size <= DriverRepository.CHUNK_SIZE })
        assertContentEquals(source, output.toByteArray())
        assertEquals(Sha256.digestHex(source), Sha256.digestHex(output.toByteArray()))
    }

    @Test
    fun shortReadsAreCombinedBeforeRootTransfer() {
        val source = ByteArray(DriverRepository.CHUNK_SIZE * 2 + 17) { (it * 37).toByte() }
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var digest = ""

        runSuspend {
            digest = DriverRepository.transfer(
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
            result = DriverRepository.publish(
                "a".repeat(64),
                listOf(DriverFileInfo("meta.json", true), DriverFileInfo(libraryName, true)),
                mapOf("libraryName" to libraryName, "abi" to "arm64-v8a"),
                0L,
            ) { _, _ -> error("must reject before transfer") }
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
