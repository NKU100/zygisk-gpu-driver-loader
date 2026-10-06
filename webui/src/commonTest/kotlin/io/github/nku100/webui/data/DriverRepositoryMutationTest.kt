package io.github.nku100.webui.data

import io.github.nku100.webui.ModuleInfo
import io.github.nku100.webui.platform.ShellResult
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriverRepositoryMutationTest {
    @Test
    fun duplicateImportRejectsMissingOrChangedDependency() {
        for (missing in listOf(true, false)) {
            val shell = InMemoryRootShell()
            val dependencies = mapOf("notgsl.so" to arm64ElfHeader())
            val first = assertIs<DriverImportResult.Accepted>(completed {
                publish(shell, 'a', 1L, extraLibraries = dependencies)
            })
            val path = "${shell.root}/${first.driver.driverId}/notgsl.so"
            if (missing) shell.files.remove(path) else shell.files[path] = arm64ElfHeader() + byteArrayOf(1)
            val result = completed { publish(shell, 'a', 2L, extraLibraries = dependencies) }
            assertEquals(DriverArchiveError.STORAGE_ERROR, assertIs<DriverImportResult.Rejected>(result).error)
            assertEquals(1L, shell.index().single().importedAtEpochMillis)
            assertFalse(shell.directories.any { it.startsWith("${shell.root}/.stage-") })
        }
    }

    @Test
    fun metadataCannotOverrideWrongElfArchitectureOrLibraryType() {
        for (header in listOf(
            arm64ElfHeader().apply { this[4] = 1 },
            arm64ElfHeader().apply { this[5] = 2 },
            arm64ElfHeader().apply { this[16] = 2 },
            arm64ElfHeader().apply { this[18] = 0x3e },
            arm64ElfHeader().copyOf(63),
        )) {
            val shell = InMemoryRootShell()
            val result = completed { publish(shell, 'a', 1L, libraryBytes = header) }
            assertEquals(DriverArchiveError.UNSUPPORTED_ABI, assertIs<DriverImportResult.Rejected>(result).error)
            assertTrue(shell.index().isEmpty())
        }
    }
    @Test
    fun importsBundledDependencyWithoutChangingItsBytes() {
        val shell = InMemoryRootShell()
        val dependency = arm64ElfHeader() + byteArrayOf(7, 8, 9)
        val result = completed { publish(shell, 'a', 1L, extraLibraries = mapOf("notgsl.so" to dependency)) }
        val driver = assertIs<DriverImportResult.Accepted>(result).driver
        kotlin.test.assertContentEquals(dependency, shell.files["${shell.root}/${driver.driverId}/notgsl.so"])
    }

    @Test
    fun invalidBundledDependencyCannotPublishDriver() {
        val shell = InMemoryRootShell()
        val result = completed { publish(shell, 'a', 1L, extraLibraries = mapOf("notgsl.so" to byteArrayOf(1))) }
        assertEquals(DriverArchiveError.UNSUPPORTED_ABI, assertIs<DriverImportResult.Rejected>(result).error)
        assertTrue(shell.index().isEmpty())
    }
    @Test
    fun invalidElfCannotPublishEvenWhenMetadataClaimsArm64() {
        val shell = InMemoryRootShell()
        val result = completed { publish(shell, 'a', 1L, libraryBytes = byteArrayOf(1, 2, 3)) }
        assertEquals(DriverArchiveError.UNSUPPORTED_ABI, assertIs<DriverImportResult.Rejected>(result).error)
        assertTrue(shell.index().isEmpty())
        assertFalse(shell.directories.any { it.startsWith("${shell.root}/.stage-") })
    }

    @Test
    fun detectsArm64FromLibraryWithNoMetadataAbi() {
        val shell = InMemoryRootShell()
        val result = completed { publish(shell, 'a', 1L, includeAbi = false) }
        assertEquals("arm64-v8a", assertIs<DriverImportResult.Accepted>(result).driver.abi)
        assertEquals(1, shell.index().size)
    }

    @Test
    fun importReReadsIndexAfterAnotherImportPublishes() {
        val shell = InMemoryRootShell()
        var resumeFirst: Continuation<Unit>? = null
        val first = start { publish(shell, 'a', 100L) { suspendCoroutine { resumeFirst = it } } }
        assertFalse(first.finished)

        val second = completed { publish(shell, 'b', 200L) }
        assertIs<DriverImportResult.Accepted>(second)
        assertNotNull(resumeFirst).resume(Unit)

        assertIs<DriverImportResult.Accepted>(first.value())
        assertEquals(setOf(second.driver.driverId, (first.value() as DriverImportResult.Accepted).driver.driverId),
            shell.index().map { it.driverId }.toSet())
    }

    @Test
    fun importDoesNotRestoreADeletedRecordFromItsOldSnapshot() {
        val shell = InMemoryRootShell()
        val existing = completed { publish(shell, 'c', 50L) } as DriverImportResult.Accepted
        var resumeImport: Continuation<Unit>? = null
        val pending = start { publish(shell, 'a', 100L) { suspendCoroutine { resumeImport = it } } }
        assertEquals(DriverDeleteResult.DELETED,
            completed { DriverRepository.deleteStored(existing.driver.driverId, shell::exec) })
        assertNotNull(resumeImport).resume(Unit)

        val imported = assertIs<DriverImportResult.Accepted>(pending.value())
        assertEquals(listOf(imported.driver.driverId), shell.index().map { it.driverId })
    }

    @Test
    fun existingFinalDirectoryIsNeverOverwritten() {
        val shell = InMemoryRootShell()
        val info = accepted('a')
        shell.directories += "${shell.root}/${info.driverId}"

        val result = completed { publish(shell, 'a', 100L) }

        assertEquals(DriverArchiveError.STORAGE_ERROR, assertIs<DriverImportResult.Rejected>(result).error)
        assertTrue(shell.index().isEmpty())
        assertTrue(shell.directories.contains("${shell.root}/${info.driverId}"))
        assertFalse(shell.directories.any { it.startsWith("${shell.root}/${info.driverId}/.stage-") })
    }

    @Test
    fun publishesPreparedFilesWithoutBase64Writes() {
        val shell = InMemoryRootShell()
        val library = arm64ElfHeader() + byteArrayOf(3, 5, 7)

        val result = completed { publish(shell, 'a', 1L, libraryBytes = library) }

        val driver = assertIs<DriverImportResult.Accepted>(result).driver
        assertContentEquals(library, shell.files["${shell.root}/${driver.driverId}/liba.so"])
        assertFalse(shell.commands.any { it.contains("base64 -d") && it.contains(".stage-") })
    }

    @Test
    fun duplicatePreparedImportPreservesStoredRecord() {
        val shell = InMemoryRootShell()
        val first = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 1234L) }).driver

        val duplicate = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 9999L) }).driver

        assertEquals(first, duplicate)
        assertEquals(listOf(first), completed { DriverRepository.listStored(shell::exec) })
        assertEquals(1, shell.index().size)
    }

    @Test
    fun stagedFileHashMismatchIsRejected() {
        val shell = InMemoryRootShell()
        val prepared = prepared(shell, 'a', libraryBytes = arm64ElfHeader(), corruptLibraryHash = true)

        val result = completed { DriverRepository.publishPrepared(prepared, 1L, shell::exec) }

        assertEquals(DriverArchiveError.TRANSFER_FAILED, assertIs<DriverImportResult.Rejected>(result).error)
        assertTrue(shell.index().isEmpty())
        assertFalse(shell.directories.any { it == "${shell.root}/${prepared.stageName}" })
    }

    @Test
    fun failedPreparedImportPreservesIndexAndBindings() {
        val shell = InMemoryRootShell()
        val existing = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'b', 4L) }).driver
        val config = ModuleConfig(packageSettings = mapOf("example.app" to PackageSettings(driverId = existing.driverId)))
        shell.files[ModuleInfo.CONFIG_PATH] = Json.encodeToString(config).encodeToByteArray()
        val candidate = prepared(shell, 'a')
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${shell.root}/index.json'") }

        val result = completed { DriverRepository.publishPrepared(candidate, 5L, shell::exec) }

        assertEquals(DriverArchiveError.STORAGE_ERROR, assertIs<DriverImportResult.Rejected>(result).error)
        assertEquals(listOf(existing.driverId), shell.index().map { it.driverId })
        assertEquals(config, Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)))
        assertFalse("${shell.root}/${accepted('a').driverId}" in shell.directories)
        assertFalse(shell.directories.any { it == "${shell.root}/${candidate.stageName}" })
    }

    @Test
    fun failedIndexRenameRemovesOnlyOwnedStage() {
        val shell = InMemoryRootShell()
        val candidate = prepared(shell, 'a')
        val unrelatedStage = "${shell.root}/.stage-deadbeef"
        shell.directories += unrelatedStage
        shell.files["$unrelatedStage/keep.txt"] = byteArrayOf(1, 2, 3)
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${shell.root}/index.json'") }

        val result = completed { DriverRepository.publishPrepared(candidate, 1L, shell::exec) }

        assertEquals(DriverArchiveError.STORAGE_ERROR, assertIs<DriverImportResult.Rejected>(result).error)
        assertFalse("${shell.root}/${candidate.stageName}" in shell.directories)
        assertTrue(unrelatedStage in shell.directories)
        assertTrue("$unrelatedStage/keep.txt" in shell.files)
        assertTrue(shell.index().isEmpty())
    }

    @Test
    fun failedIndexPublicationDoesNotExposeIncompleteImport() {
        val shell = InMemoryRootShell()
        val driverId = accepted('a').driverId
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${shell.root}/index.json'") }

        val result = completed { publish(shell, 'a', 100L) }

        assertEquals(DriverArchiveError.STORAGE_ERROR, assertIs<DriverImportResult.Rejected>(result).error)
        assertTrue(shell.index().isEmpty())
        assertFalse("${shell.root}/$driverId" in shell.directories)
        assertFalse(shell.directories.any { it.startsWith("${shell.root}/.stage-") })
    }

    @Test
    fun failedDeleteIndexPublicationRestoresDirectoryAndOldIndex() {
        val shell = InMemoryRootShell()
        val driver = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 100L) }).driver
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${shell.root}/index.json'") }

        val result = completed { DriverRepository.deleteStored(driver.driverId, shell::exec) }

        assertEquals(DriverDeleteResult.IO_ERROR, result)
        assertEquals(listOf(driver.driverId), shell.index().map { it.driverId })
        assertTrue("${shell.root}/${driver.driverId}" in shell.directories)
    }

    @Test
    fun newAndDuplicateImportReturnPersistedMetadata() {
        val shell = InMemoryRootShell()
        val first = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 1234L) })
        val duplicate = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 9999L) })

        assertEquals(1234L, first.driver.importedAtEpochMillis)
        assertEquals("a".repeat(64), first.driver.archiveSha256)
        assertEquals(first.driver, duplicate.driver)
        assertEquals(first.driver, completed { DriverRepository.listStored(shell::exec) }.single())
    }

    @Test
    fun deleteWaitsForAtomicConfigSaveThenRefusesBoundDriver() {
        val shell = InMemoryRootShell()
        val imported = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 1L) })
        val binding = ModuleConfig(packageSettings = mapOf("example.app" to PackageSettings(driverId = imported.driver.driverId)))
        shell.files[ModuleInfo.CONFIG_PATH] = Json.encodeToString(ModuleConfig()).encodeToByteArray()
        shell.pauseOn = { it.startsWith("mv ") && it.endsWith("'${ModuleInfo.CONFIG_PATH}'") }
        val saving = start { ConfigRepository.saveWith(binding, shell::exec) }
        assertFalse(saving.finished)
        assertEquals("", Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)).packageSettings["example.app"]?.driverId.orEmpty())

        val deleting = start { DriverRepository.deleteStored(imported.driver.driverId, shell::exec) }
        assertFalse(deleting.finished)
        shell.resume()

        saving.value()
        assertEquals(DriverDeleteResult.BOUND, deleting.value())
        assertEquals(imported.driver.driverId,
            Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)).packageSettings.getValue("example.app").driverId)
        assertEquals(listOf(imported.driver.driverId), shell.index().map { it.driverId })
    }

    @Test
    fun deleteWithResetClearsEveryBindingAndPreservesOtherPackageSettings() {
        val shell = InMemoryRootShell()
        val imported = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 1L) })
        val original = ModuleConfig(
            enabled = false,
            targetPackages = listOf("example.active"),
            packageSettings = mapOf(
                "example.active" to PackageSettings(driverId = imported.driver.driverId, note = "active"),
                "example.inactive" to PackageSettings(driverId = imported.driver.driverId, note = "inactive"),
                "example.other" to PackageSettings(driverId = "other-driver", note = "untouched"),
            ),
        )
        shell.files[ModuleInfo.CONFIG_PATH] = Json.encodeToString(original).encodeToByteArray()

        val outcome = completed {
            DriverRepository.deleteStoredAndResetBindings(imported.driver.driverId, shell::exec)
        }

        val expected = original.copy(packageSettings = original.packageSettings.mapValues { (_, settings) ->
            if (settings.driverId == imported.driver.driverId) settings.copy(driverId = "") else settings
        })
        assertEquals(DriverDeleteResult.DELETED, outcome.result)
        assertEquals(setOf("example.active", "example.inactive"), outcome.resetPackageNames.toSet())
        assertEquals(expected, outcome.updatedConfig)
        assertEquals(expected, Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)))
        assertTrue(shell.index().isEmpty())
    }

    @Test
    fun failedConfigWriteAbortsDeleteAndKeepsBindings() {
        val shell = InMemoryRootShell()
        val imported = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 1L) })
        val original = ModuleConfig(packageSettings = mapOf(
            "example.app" to PackageSettings(driverId = imported.driver.driverId),
        ))
        shell.files[ModuleInfo.CONFIG_PATH] = Json.encodeToString(original).encodeToByteArray()
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${ModuleInfo.CONFIG_PATH}'") }

        val outcome = completed {
            DriverRepository.deleteStoredAndResetBindings(imported.driver.driverId, shell::exec)
        }

        assertEquals(DriverDeleteResult.IO_ERROR, outcome.result)
        assertEquals(emptyList(), outcome.resetPackageNames)
        assertEquals(null, outcome.updatedConfig)
        assertEquals(original, Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)))
        assertEquals(listOf(imported.driver.driverId), shell.index().map { it.driverId })
    }

    @Test
    fun failedDriverRemovalKeepsSuccessfulSystemDriverResetVisible() {
        val shell = InMemoryRootShell()
        val imported = assertIs<DriverImportResult.Accepted>(completed { publish(shell, 'a', 1L) })
        val original = ModuleConfig(packageSettings = mapOf(
            "example.app" to PackageSettings(driverId = imported.driver.driverId, note = "keep"),
        ))
        shell.files[ModuleInfo.CONFIG_PATH] = Json.encodeToString(original).encodeToByteArray()
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${shell.root}/index.json'") }

        val outcome = completed {
            DriverRepository.deleteStoredAndResetBindings(imported.driver.driverId, shell::exec)
        }

        val updated = original.copy(packageSettings = mapOf(
            "example.app" to PackageSettings(note = "keep"),
        ))
        assertEquals(DriverDeleteResult.IO_ERROR, outcome.result)
        assertEquals(listOf("example.app"), outcome.resetPackageNames)
        assertEquals(updated, outcome.updatedConfig)
        assertEquals(updated, Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)))
        assertEquals(listOf(imported.driver.driverId), shell.index().map { it.driverId })
    }

    @Test
    fun failedConfigRenamePreservesPreviouslyPublishedConfig() {
        val shell = InMemoryRootShell()
        val original = ModuleConfig(enabled = false)
        shell.files[ModuleInfo.CONFIG_PATH] = Json.encodeToString(original).encodeToByteArray()
        shell.failOn = { it.startsWith("mv ") && it.endsWith("'${ModuleInfo.CONFIG_PATH}'") }

        val result = start { ConfigRepository.saveWith(ModuleConfig(enabled = true), shell::exec) }.result

        assertTrue(result?.isFailure == true)
        assertEquals(original, Json.decodeFromString<ModuleConfig>(shell.text(ModuleInfo.CONFIG_PATH)))
        assertFalse(shell.files.keys.any { it.startsWith("${ModuleInfo.CONFIG_PATH}.tmp-") })
    }

    private suspend fun publish(
        shell: InMemoryRootShell,
        suffix: Char,
        importedAt: Long,
        libraryBytes: ByteArray = arm64ElfHeader(),
        includeAbi: Boolean = true,
        extraLibraries: Map<String, ByteArray> = emptyMap(),
        pause: suspend () -> Unit = {},
    ): DriverImportResult {
        val prepared = prepared(shell, suffix, libraryBytes, includeAbi, extraLibraries)
        pause()
        return DriverRepository.publishPrepared(prepared, importedAt, shell::exec)
    }

    private fun prepared(
        shell: InMemoryRootShell,
        suffix: Char,
        libraryBytes: ByteArray = arm64ElfHeader(),
        includeAbi: Boolean = true,
        extraLibraries: Map<String, ByteArray> = emptyMap(),
        corruptLibraryHash: Boolean = false,
    ): DriverPreparedImport {
        val library = "lib$suffix.so"
        val meta = """{"name":"Driver $suffix","libraryName":"$library"${if (includeAbi) ",\"abi\":\"arm64-v8a\"" else ""}}"""
        val metadata = mapOf("name" to "Driver $suffix", "libraryName" to library) +
            if (includeAbi) mapOf("abi" to "arm64-v8a") else emptyMap()
        val contents = mapOf("meta.json" to meta.encodeToByteArray(), library to libraryBytes) + extraLibraries
        val entries = contents.keys.map { DriverFileInfo(it, true) }
        val stageName = shell.seedStage(contents)
        val hashes = contents.mapValues { Sha256.digestHex(it.value) }.toMutableMap()
        if (corruptLibraryHash) hashes[library] = "0".repeat(64)
        return DriverPreparedImport(suffix.toString().repeat(64), entries, metadata, hashes, stageName)
    }

    private fun accepted(suffix: Char): DriverInfo =
        (DriverArchivePolicy.validate(suffix.toString().repeat(64),
            listOf(DriverFileInfo("meta.json", true), DriverFileInfo("lib$suffix.so", true)),
            mapOf("libraryName" to "lib$suffix.so", "abi" to "arm64-v8a")) as DriverImportResult.Accepted).driver

    private class Pending<T> {
        var result: Result<T>? = null
        val finished get() = result != null
        fun value(): T = requireNotNull(result).getOrThrow()
    }

    private fun <T> start(block: suspend () -> T): Pending<T> = Pending<T>().also { pending ->
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { pending.result = result }
        })
    }

    private fun <T> completed(block: suspend () -> T): T = start(block).value()
}

private class InMemoryRootShell {
    val root = "/data/adb/${ModuleInfo.MODULE_ID}/drivers"
    private val indexPath = "$root/index.json"
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf(root)
    val commands = mutableListOf<String>()
    var pauseOn: ((String) -> Boolean)? = null
    var failOn: ((String) -> Boolean)? = null
    private var blocked: Continuation<Unit>? = null
    private var stageCounter = 0
    private val quoted = Regex("'([^']*)'")

    fun text(path: String) = files.getValue(path).decodeToString()
    fun index(): List<DriverRecord> = files[indexPath]?.decodeToString()?.let { Json.decodeFromString<List<DriverRecord>>(it) } ?: emptyList()
    fun resume() { requireNotNull(blocked).also { blocked = null }.resume(Unit) }

    fun seedStage(contents: Map<String, ByteArray>): String {
        val stageName = ".stage-${(++stageCounter).toString(16)}"
        val stagePath = "$root/$stageName"
        directories += stagePath
        contents.forEach { (name, bytes) -> files["$stagePath/$name"] = bytes }
        return stageName
    }

    suspend fun exec(command: String): ShellResult {
        commands += command
        if (pauseOn?.invoke(command) == true) {
            pauseOn = null
            suspendCoroutine<Unit> { blocked = it }
        }
        if (failOn?.invoke(command) == true) return ShellResult(1, "", "injected failure")
        val args = quoted.findAll(command).map { it.groupValues[1] }.toList()
        fun ok(stdout: String = "") = ShellResult(0, stdout, "")
        fun failed() = ShellResult(1, "", "missing or occupied")
        return when {
            command.startsWith("if [ -f ") -> ok(files[args.first()]?.decodeToString().orEmpty())
            command.startsWith("cat ") -> files[args.first()]?.decodeToString()?.let(::ok) ?: failed()
            command.startsWith("mkdir -p ") -> {
                directories += args[0]
                if (!directories.add(args[1])) failed() else ok()
            }
            command.startsWith(": > ") -> { files[args[0]] = byteArrayOf(); ok() }
            command.startsWith("printf '%s' ") -> {
                val path = args.last()
                files[path] = files.getValue(path) + Base64.decode(args[1])
                ok()
            }
            command.startsWith("od -An ") -> files[args.last()]?.let { bytes ->
                ok(bytes.take(64).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0') })
            } ?: failed()
            command.startsWith("sha256sum ") -> files[args[0]]?.let { ok("${Sha256.digestHex(it)}  ${args[0]}\n") } ?: failed()
            command.startsWith("test -f ") -> if (args.all { it in files }) ok() else failed()
            command.startsWith("test -d ") -> if (args.first() in directories) ok() else failed()
            command.startsWith("test -e ") -> if (args.first() in directories || args.first() in files) ok() else failed()
            command.startsWith("if [ -e ") -> {
                if (command.contains("rm -rf")) {
                    remove(args.last())
                    ok()
                } else {
                    val destination = args.last()
                    if (destination in directories || destination in files) failed() else move(args[2], destination)
                }
            }
            command.startsWith("mv ") -> move(args[0], args[1])
            command.startsWith("rm -rf ") || command.startsWith("if [ -d ") -> {
                val path = args.first()
                remove(path)
                ok()
            }
            command.startsWith("rm -f ") -> { files.remove(args[0]); ok() }
            else -> error("Unexpected shell command: $command")
        }
    }

    private fun remove(path: String) {
        directories.removeAll { it == path || it.startsWith("$path/") }
        files.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    private fun move(from: String, to: String): ShellResult {
        if (from in files) {
            files[to] = files.remove(from)!!
            return ShellResult(0, "", "")
        }
        if (from !in directories || to in directories) return ShellResult(1, "", "missing or occupied")
        val movedDirectories = directories.filter { it == from || it.startsWith("$from/") }
        val movedFiles = files.filterKeys { it.startsWith("$from/") }
        movedDirectories.forEach { directories.remove(it) }
        movedFiles.keys.forEach { files.remove(it) }
        movedDirectories.forEach { directories += to + it.removePrefix(from) }
        movedFiles.forEach { (path, bytes) -> files[to + path.removePrefix(from)] = bytes }
        return ShellResult(0, "", "")
    }
}
