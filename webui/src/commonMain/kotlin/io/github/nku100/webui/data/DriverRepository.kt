package io.github.nku100.webui.data

import io.github.nku100.webui.ModuleInfo
import io.github.nku100.webui.platform.PlatformBridge
import io.github.nku100.webui.platform.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class DriverRecord(
    val driverId: String,
    val name: String,
    val libraryName: String,
    val abi: String,
    val importedAtEpochMillis: Long,
    val archiveSha256: String,
) {
    fun info() = DriverInfo(driverId, name, libraryName, abi, importedAtEpochMillis, archiveSha256)
}

enum class DriverDeleteResult { DELETED, BOUND, NOT_FOUND, INVALID_ID, IO_ERROR }

data class DriverDeleteOutcome(
    val result: DriverDeleteResult,
    val updatedConfig: ModuleConfig? = null,
    val resetPackageNames: List<String> = emptyList(),
)

internal class DriverStoreException(val code: DriverArchiveError) : Exception(code.name)
internal typealias RootCommand = suspend (String) -> ShellResult

/** Root-owned driver registry. Only completed directories referenced by index.json are visible. */
object DriverRepository {
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val root = "/data/adb/${ModuleInfo.MODULE_ID}/drivers"
    private val indexPath = "$root/index.json"
    private val idPattern = Regex("[0-9a-f]{64}:arm64-v8a:[0-9a-f]+")
    private val libraryPattern = Regex("[^/\\\\\u0000]+")
    private const val IMPORT_PROTOCOL = "DRIVER_IMPORT_V1"
    private const val HELPER_CLASS = "io.github.nku100.gpudriver.importer.DriverPathHelper"

    suspend fun listZipDirectory(path: String): DriverDirectory =
        listZipDirectory(path, PlatformBridge::execDriverImport)

    internal suspend fun listZipDirectory(path: String, execute: RootCommand): DriverDirectory {
        val result = execute(helperCommand("list", path))
        if (result.errno != 0 && result.stdout.isBlank()) throw DriverPathException(DriverPathError.STORAGE_ERROR)
        return parseDirectoryProtocol(path, result.stdout)
    }

    suspend fun importDriverZip(path: String): DriverImportResult =
        importDriverZip(path, PlatformBridge.currentTimeMillis(), PlatformBridge::execDriverImport)

    internal suspend fun importDriverZip(
        path: String,
        importedAtEpochMillis: Long,
        execute: RootCommand,
    ): DriverImportResult {
        val operationNonce = nonce()
        val stageName = ".stage-$operationNonce"
        val snapshot = "$root/.snapshot-$operationNonce.zip"
        val stage = "$root/$stageName"
        var helperStarted = false
        try {
            val available = execute(
                "test ! -e ${quote(snapshot)} && test ! -L ${quote(snapshot)} && " +
                    "test ! -e ${quote(stage)} && test ! -L ${quote(stage)}",
            )
            if (available.errno != 0) return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
            helperStarted = true
            val result = execute(helperCommand("prepare", path, root, operationNonce))
            if (result.errno != 0 && result.stdout.isBlank()) {
                return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
            }
            val prepared = parsePreparedProtocol(result.stdout)
            if (result.errno != 0 || prepared.stageName != stageName) {
                throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
            }
            return publishPrepared(prepared, importedAtEpochMillis, execute)
        } catch (error: DriverStoreException) {
            return DriverImportResult.Rejected(error.code)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
        } finally {
            if (helperStarted) {
                withContext(NonCancellable) {
                    runCatching { execute("if [ -e ${quote(snapshot)} ] || [ -L ${quote(snapshot)} ]; then rm -f ${quote(snapshot)}; fi") }
                    runCatching { execute("if [ -e ${quote(stage)} ] || [ -L ${quote(stage)} ]; then rm -rf ${quote(stage)}; fi") }
                }
            }
        }
    }

    internal suspend fun publishPrepared(
        prepared: DriverPreparedImport,
        importedAtEpochMillis: Long,
        execute: RootCommand = PlatformBridge::exec,
    ): DriverImportResult {
        if (!Regex("\\.stage-[0-9a-f]{1,64}").matches(prepared.stageName)) {
            return DriverImportResult.Rejected(DriverArchiveError.INVALID_ZIP)
        }
        val stage = "$root/${prepared.stageName}"
        try {
            if (!Regex("[0-9a-f]{64}").matches(prepared.archiveSha256)) {
                throw DriverStoreException(DriverArchiveError.INVALID_ARCHIVE_HASH)
            }
            val validation = DriverArchivePolicy.validate(prepared.archiveSha256, prepared.entries, prepared.metaJson)
            if (validation !is DriverImportResult.Accepted) return validation
            val driver = validation.driver
            if (!idPattern.matches(driver.driverId) || driver.driverId.length > 240 ||
                !libraryPattern.matches(driver.libraryName)) {
                throw DriverStoreException(DriverArchiveError.INVALID_ENTRY_PATH)
            }
            val expectedFiles = prepared.entries.filter {
                it.isRegularFile && (it.path == "meta.json" || it.path.endsWith(".so"))
            }.map { it.path }.toSet()
            if (prepared.fileHashes.keys != expectedFiles || prepared.fileHashes.values.any {
                    !Regex("[0-9a-f]{64}").matches(it)
                }) {
                throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
            }
            val final = "$root/${driver.driverId}"
            checked("test -d ${quote(stage)} && test ! -L ${quote(stage)}", execute)
            if (parseMeta(checked("cat ${quote("$stage/meta.json")}", execute).stdout) != prepared.metaJson) {
                throw DriverStoreException(DriverArchiveError.INVALID_META_JSON_ENTRY)
            }
            for ((name, expectedHash) in prepared.fileHashes) {
                val path = "$stage/$name"
                checked("test -f ${quote(path)} && test ! -L ${quote(path)}", execute)
                if (name.endsWith(".so")) {
                    val header = parseHexBytes(checked("od -An -v -t x1 -N ${DriverElfHeader.SIZE} ${quote(path)}", execute).stdout)
                    if (!DriverElfHeader.isArm64Library(header)) {
                        throw DriverStoreException(DriverArchiveError.UNSUPPORTED_ABI)
                    }
                }
                val actualHash = checked("sha256sum ${quote(path)}", execute).stdout.substringBefore(' ').lowercase()
                if (!Regex("[0-9a-f]{64}").matches(actualHash) || actualHash != expectedHash) {
                    throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                }
            }
            return RepositoryMutationGuard.mutate {
                val latest = readIndex(execute)
                val existing = latest.firstOrNull { it.driverId == driver.driverId }
                if (existing != null) {
                    for ((name, expectedHash) in prepared.fileHashes) {
                        val installedHash = checked("sha256sum ${quote("$final/$name")}", execute)
                            .stdout.substringBefore(' ').lowercase()
                        if (installedHash != expectedHash) throw DriverStoreException(DriverArchiveError.STORAGE_ERROR)
                    }
                    return@mutate DriverImportResult.Accepted(existing.info())
                }
                val occupied = execute("test -e ${quote(final)} || test -L ${quote(final)}")
                if (occupied.errno == 0) throw DriverStoreException(DriverArchiveError.STORAGE_ERROR)
                val record = DriverRecord(driver.driverId, driver.name, driver.libraryName, driver.abi,
                    importedAtEpochMillis, prepared.archiveSha256.lowercase())
                val preparedIndex = prepareIndex(latest + record, execute)
                var published = false
                try {
                    checked("if [ -e ${quote(final)} ] || [ -L ${quote(final)} ]; then exit 17; fi; mv ${quote(stage)} ${quote(final)}", execute)
                    published = true
                    checked("mv ${quote(preparedIndex)} ${quote(indexPath)}", execute)
                    DriverImportResult.Accepted(record.info())
                } catch (error: Exception) {
                    if (published) execute("rm -rf ${quote(final)}")
                    throw error
                } finally {
                    execute("rm -f ${quote(preparedIndex)}")
                }
            }
        } catch (error: DriverStoreException) {
            return DriverImportResult.Rejected(error.code)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
        } finally {
            withContext(NonCancellable) {
                runCatching { execute("if [ -e ${quote(stage)} ] || [ -L ${quote(stage)} ]; then rm -rf ${quote(stage)}; fi") }
            }
        }
    }

    internal fun parseDirectoryProtocol(path: String, output: String): DriverDirectory {
        return try {
            val lines = protocolLines(output)
            helperErrorCode(lines.firstOrNull())?.let { throw DriverPathException(pathError(it)) }
            if (lines.firstOrNull() != "$IMPORT_PROTOCOL\tOK\tLIST") {
                throw DriverPathException(DriverPathError.STORAGE_ERROR)
            }
            val entries = lines.drop(1).map { line ->
                val fields = line.split('\t')
                if (fields.size != 2) throw DriverPathException(DriverPathError.STORAGE_ERROR)
                val name = decodeProtocolText(fields[1])
                if (!isSafePathEntryName(name)) throw DriverPathException(DriverPathError.STORAGE_ERROR)
                val isDirectory = when (fields[0]) {
                    "DIR" -> true
                    "ZIP" -> false
                    else -> throw DriverPathException(DriverPathError.STORAGE_ERROR)
                }
                if (!isDirectory && !name.endsWith(".zip", ignoreCase = true)) {
                    throw DriverPathException(DriverPathError.STORAGE_ERROR)
                }
                DriverPathEntry(name, isDirectory)
            }
            DriverDirectory(path, entries)
        } catch (error: DriverPathException) {
            throw error
        } catch (_: Exception) {
            throw DriverPathException(DriverPathError.STORAGE_ERROR)
        }
    }

    internal fun parsePreparedProtocol(output: String): DriverPreparedImport {
        val lines = protocolLines(output)
        helperErrorCode(lines.firstOrNull())?.let { throw DriverStoreException(archiveError(it)) }
        if (lines.firstOrNull() != "$IMPORT_PROTOCOL\tOK\tPREPARE") {
            throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        }
        var archiveHash: String? = null
        var metaJson: Map<String, String>? = null
        var stageName: String? = null
        val entries = mutableListOf<DriverFileInfo>()
        val fileHashes = linkedMapOf<String, String>()
        val seenEntries = mutableSetOf<String>()
        for ((index, line) in lines.drop(1).withIndex()) {
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "ARCHIVE" -> {
                    if (fields.size != 2 || archiveHash != null || index != 0 ||
                        !Regex("[0-9a-f]{64}").matches(fields[1])) {
                        throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    }
                    archiveHash = fields[1]
                }
                "META" -> {
                    if (fields.size != 2 || metaJson != null || archiveHash == null || entries.isNotEmpty()) {
                        throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    }
                    metaJson = try {
                        parseMeta(decodeProtocolText(fields[1]))
                    } catch (error: DriverStoreException) {
                        throw error
                    } catch (_: Exception) {
                        throw DriverStoreException(DriverArchiveError.INVALID_META_JSON_ENTRY)
                    }
                }
                "ENTRY" -> {
                    if (fields.size != 4 || archiveHash == null || fileHashes.isNotEmpty() || stageName != null) {
                        throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    }
                    val path = decodeProtocolText(fields[1])
                    if (!seenEntries.add(path)) throw DriverStoreException(DriverArchiveError.INVALID_ENTRY_PATH)
                    val regular = parseProtocolFlag(fields[2])
                    val symlink = parseProtocolFlag(fields[3])
                    entries += DriverFileInfo(path, regular, symlink)
                }
                "FILE" -> {
                    if (fields.size != 4 || archiveHash == null || stageName != null) {
                        throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    }
                    val name = decodeProtocolText(fields[1])
                    val size = fields[2].toLongOrNull()
                    val hash = fields[3]
                    if (!seenEntries.contains(name) || size == null || size <= 0 ||
                        size > MAX_ARCHIVE_BYTES || !Regex("[0-9a-f]{64}").matches(hash) ||
                        fileHashes.put(name, hash) != null) {
                        throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    }
                }
                "STAGE" -> {
                    if (fields.size != 2 || stageName != null || index != lines.size - 2 ||
                        !Regex("\\.stage-[0-9a-f]{1,64}").matches(fields[1])) {
                        throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    }
                    stageName = fields[1]
                }
                else -> throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
            }
        }
        return DriverPreparedImport(
            archiveSha256 = archiveHash ?: throw DriverStoreException(DriverArchiveError.INVALID_ZIP),
            entries = entries,
            metaJson = metaJson ?: emptyMap(),
            fileHashes = fileHashes,
            stageName = stageName ?: throw DriverStoreException(DriverArchiveError.INVALID_ZIP),
        )
    }

    private fun protocolLines(output: String): List<String> {
        if (output.isEmpty() || !output.endsWith('\n') || '\r' in output) {
            throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        }
        val body = output.removeSuffix("\n")
        if (body.isEmpty()) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        return body.split('\n').also { lines ->
            if (lines.any { it.isEmpty() }) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        }
    }

    private fun helperErrorCode(line: String?): String? {
        val fields = line?.split('\t') ?: return null
        if (fields.size == 3 && fields[0] == IMPORT_PROTOCOL && fields[1] == "ERROR" &&
            fields[2].matches(Regex("[A-Z_]+"))) return fields[2]
        return null
    }

    private fun pathError(code: String): DriverPathError = when (code) {
        "INVALID_PATH" -> DriverPathError.INVALID_PATH
        "ACCESS_DENIED" -> DriverPathError.ACCESS_DENIED
        else -> DriverPathError.STORAGE_ERROR
    }

    private fun archiveError(code: String): DriverArchiveError = when (code) {
        "INVALID_PATH" -> DriverArchiveError.INVALID_ZIP
        "ACCESS_DENIED", "STORAGE_ERROR", "INVALID_ARGUMENT" -> DriverArchiveError.STORAGE_ERROR
        "INVALID_DRIVER_LIBRARY" -> DriverArchiveError.UNSUPPORTED_ABI
        "INVALID_ARCHIVE", "LIMIT_EXCEEDED", "UNSUPPORTED_ARCHIVE" -> DriverArchiveError.INVALID_ZIP
        else -> DriverArchiveError.INVALID_ZIP
    }

    private fun decodeProtocolText(encoded: String): String {
        val decoded = try {
            Base64.decode(encoded)
        } catch (_: Exception) {
            throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        }
        if (Base64.encode(decoded) != encoded) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        return try {
            decoded.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
        }
    }

    private fun parseProtocolFlag(value: String): Boolean = when (value) {
        "0" -> false
        "1" -> true
        else -> throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
    }

    private fun parseHexBytes(output: String): ByteArray = try {
        output.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { token ->
            token.toInt(16).also { require(it in 0..255) }.toByte()
        }.toByteArray()
    } catch (_: Exception) {
        byteArrayOf()
    }

    private fun isSafePathEntryName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

    private fun helperCommand(vararg arguments: String): String {
        val dexPath = ModuleInfo.MODULE_PROP_PATH.substringBeforeLast('/') + "/driver-importer.dex"
        val preferredVm = "/apex/com.android.art/bin/dalvikvm64"
        val fallbackVm = "/system/bin/dalvikvm64"
        fun invocation(vm: String) = listOf(vm, "-cp", dexPath, HELPER_CLASS, *arguments)
            .joinToString(" ") { quote(it) }
        return "if [ -x ${quote(preferredVm)} ]; then ${invocation(preferredVm)}; " +
            "elif [ -x ${quote(fallbackVm)} ]; then ${invocation(fallbackVm)}; else exit 127; fi"
    }

    suspend fun listDrivers(): List<DriverInfo> = PlatformBridge.listDrivers()

    suspend fun deleteDriver(driverId: String, resetBindings: Boolean = false): DriverDeleteOutcome =
        if (resetBindings) deleteStoredAndResetBindings(driverId)
        else DriverDeleteOutcome(PlatformBridge.deleteDriver(driverId))

    internal suspend fun deleteStoredAndResetBindings(
        driverId: String,
        execute: RootCommand = PlatformBridge::exec,
    ): DriverDeleteOutcome = deleteStoredInternal(driverId, execute, resetBindings = true)

    internal fun canDelete(driverId: String, config: ModuleConfig): Boolean =
        config.packageSettings.values.none { it.driverId == driverId }

    internal fun parseMeta(raw: String): Map<String, String> = try {
        json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
    } catch (_: Exception) {
        throw DriverStoreException(DriverArchiveError.INVALID_META_JSON_ENTRY)
    }

    internal suspend fun readIndex(execute: RootCommand = PlatformBridge::exec): List<DriverRecord> {
        val result = checked("if [ -f ${quote(indexPath)} ]; then cat ${quote(indexPath)}; fi", execute)
        if (result.stdout.isBlank()) return emptyList()
        return try { json.decodeFromString(result.stdout) } catch (_: Exception) {
            throw DriverStoreException(DriverArchiveError.STORAGE_ERROR)
        }
    }

    internal suspend fun listStored(execute: RootCommand = PlatformBridge::exec): List<DriverInfo> = readIndex(execute).filter { record ->
        if (!isSafeRecord(record)) return@filter false
        val dir = "$root/${record.driverId}"
        execute("test -f ${quote("$dir/meta.json")} && test -f ${quote("$dir/${record.libraryName}")}").errno == 0
    }.map { it.info() }

    internal suspend fun deleteStored(
        driverId: String,
        execute: RootCommand = PlatformBridge::exec,
    ): DriverDeleteResult = deleteStoredInternal(driverId, execute, resetBindings = false).result

    private suspend fun deleteStoredInternal(
        driverId: String,
        execute: RootCommand,
        resetBindings: Boolean,
    ): DriverDeleteOutcome {
        if (!idPattern.matches(driverId)) return DriverDeleteOutcome(DriverDeleteResult.INVALID_ID)
        return RepositoryMutationGuard.mutate {
            var updatedConfig: ModuleConfig? = null
            var resetPackageNames: List<String> = emptyList()
            try {
                val configPath = ConfigRepository.configPath
                val configResult = checked("if [ -f ${quote(configPath)} ]; then cat ${quote(configPath)}; fi", execute)
                val config = if (configResult.stdout.isBlank()) ModuleConfig() else
                    json.decodeFromString<ModuleConfig>(configResult.stdout)
                val boundPackageNames = config.packageSettings
                    .filterValues { it.driverId == driverId }
                    .keys
                    .sorted()
                if (boundPackageNames.isNotEmpty() && !resetBindings) {
                    return@mutate DriverDeleteOutcome(DriverDeleteResult.BOUND)
                }
                val records = readIndex(execute)
                if (records.none { it.driverId == driverId }) {
                    return@mutate DriverDeleteOutcome(DriverDeleteResult.NOT_FOUND)
                }
                if (boundPackageNames.isNotEmpty()) {
                    val newConfig = config.copy(packageSettings = config.packageSettings.mapValues { (_, settings) ->
                        if (settings.driverId == driverId) settings.copy(driverId = "") else settings
                    })
                    ConfigRepository.saveWithinMutation(newConfig, execute)
                    updatedConfig = newConfig
                    resetPackageNames = boundPackageNames
                }
                val directory = "$root/$driverId"
                val trash = "$root/.delete-${nonce()}"
                val preparedIndex = prepareIndex(records.filterNot { it.driverId == driverId }, execute)
                try {
                    checked("mv ${quote(directory)} ${quote(trash)}", execute)
                    try {
                        checked("mv ${quote(preparedIndex)} ${quote(indexPath)}", execute)
                    } catch (e: Exception) {
                        execute("mv ${quote(trash)} ${quote(directory)}")
                        throw e
                    }
                    checked("rm -rf ${quote(trash)}", execute)
                    DriverDeleteOutcome(DriverDeleteResult.DELETED, updatedConfig, resetPackageNames)
                } finally {
                    runCatching { execute("rm -f ${quote(preparedIndex)}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DriverDeleteOutcome(DriverDeleteResult.IO_ERROR, updatedConfig, resetPackageNames)
            }
        }
    }

    private suspend fun prepareIndex(records: List<DriverRecord>, execute: RootCommand): String {
        val temp = "$root/.index-${nonce()}"
        try {
            checked(": > ${quote(temp)}", execute)
            val bytes = json.encodeToString(records).encodeToByteArray()
            var offset = 0
            RootFileTransfer.transfer(
                read = { buffer ->
                    if (offset == bytes.size) -1 else {
                        val count = minOf(buffer.size, bytes.size - offset)
                        bytes.copyInto(buffer, 0, offset, offset + count)
                        offset += count
                        count
                    }
                },
                appendBase64 = { chunk -> checked("printf '%s' ${quote(chunk)} | base64 -d >> ${quote(temp)}", execute) },
            )
            return temp
        } catch (e: Exception) {
            execute("rm -f ${quote(temp)}")
            throw e
        }
    }

    private suspend fun checked(command: String, execute: RootCommand): ShellResult {
        val result = execute(command)
        if (result.errno != 0) throw DriverStoreException(DriverArchiveError.STORAGE_ERROR)
        return result
    }

    private fun isSafeRecord(record: DriverRecord) =
        idPattern.matches(record.driverId) && libraryPattern.matches(record.libraryName) &&
            record.abi == DriverArchivePolicy.SUPPORTED_ABI

    private fun nonce(): String = Random.nextLong().toULong().toString(16)

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
