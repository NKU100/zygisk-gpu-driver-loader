package io.github.nku100.webui.data

import io.github.nku100.webui.ModuleInfo
import io.github.nku100.webui.platform.PlatformBridge
import io.github.nku100.webui.platform.ShellResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.random.Random

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

internal class DriverStoreException(val code: DriverArchiveError) : Exception(code.name)
internal typealias RootCommand = suspend (String) -> ShellResult

/** Root-owned driver registry. Only completed directories referenced by index.json are visible. */
object DriverRepository {
    const val CHUNK_SIZE = 12 * 1024
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_META_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val root = "/data/adb/${ModuleInfo.MODULE_ID}/drivers"
    private val indexPath = "$root/index.json"
    private val idPattern = Regex("[0-9a-f]{64}:arm64-v8a:[0-9a-f]+")
    private val libraryPattern = Regex("[^/\\\\\u0000]+")

    suspend fun importDriverZip(): DriverImportResult = PlatformBridge.importDriverZip()

    suspend fun listDrivers(): List<DriverInfo> = PlatformBridge.listDrivers()

    suspend fun deleteDriver(driverId: String): DriverDeleteResult = PlatformBridge.deleteDriver(driverId)

    internal fun canDelete(driverId: String, config: ModuleConfig): Boolean =
        config.packageSettings.values.none { it.driverId == driverId }

    internal suspend fun transfer(
        read: suspend (ByteArray) -> Int,
        appendBase64: suspend (String) -> Unit,
    ): String {
        val buffer = ByteArray(CHUNK_SIZE)
        val hash = Sha256()
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
            require(count <= CHUNK_SIZE)
            hash.update(buffer, count)
            appendBase64(Base64.encode(buffer.copyOf(count)))
        }
        return hash.hexDigest()
    }

    internal fun parseMeta(raw: String): Map<String, String> = try {
        json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
    } catch (_: Exception) {
        throw DriverStoreException(DriverArchiveError.INVALID_META_JSON_ENTRY)
    }

    internal fun checkArchiveSize(size: Long) {
        if (size <= 0 || size > MAX_ARCHIVE_BYTES) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
    }

    internal fun checkMetaSize(size: Int) {
        if (size > MAX_META_BYTES) throw DriverStoreException(DriverArchiveError.INVALID_META_JSON_ENTRY)
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
    ): DriverDeleteResult {
        if (!idPattern.matches(driverId)) return DriverDeleteResult.INVALID_ID
        return RepositoryMutationGuard.mutate {
            try {
                val configPath = ConfigRepository.configPath
                val configResult = checked("if [ -f ${quote(configPath)} ]; then cat ${quote(configPath)}; fi", execute)
                val config = if (configResult.stdout.isBlank()) ModuleConfig() else
                    json.decodeFromString<ModuleConfig>(configResult.stdout)
                if (!canDelete(driverId, config)) return@mutate DriverDeleteResult.BOUND
                val records = readIndex(execute)
                if (records.none { it.driverId == driverId }) return@mutate DriverDeleteResult.NOT_FOUND
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
                    DriverDeleteResult.DELETED
                } finally {
                    execute("rm -f ${quote(preparedIndex)}")
                }
            } catch (_: Exception) {
                DriverDeleteResult.IO_ERROR
            }
        }
    }

    /** [streamFile] emits independently encoded chunks from a validated ZIP entry. */
    internal suspend fun publish(
        archiveSha256: String,
        entries: List<DriverFileInfo>,
        metaJson: Map<String, String>,
        importedAtEpochMillis: Long,
        execute: RootCommand = PlatformBridge::exec,
        streamFile: suspend (String, suspend (String) -> Unit) -> Unit,
    ): DriverImportResult {
        val validation = DriverArchivePolicy.validate(archiveSha256, entries, metaJson)
        if (validation !is DriverImportResult.Accepted) return validation
        val driver = validation.driver
        if (!idPattern.matches(driver.driverId) || driver.driverId.length > 240 ||
            !libraryPattern.matches(driver.libraryName)) {
            return DriverImportResult.Rejected(DriverArchiveError.INVALID_ENTRY_PATH)
        }
        val stage = "$root/.stage-${nonce()}"
        val final = "$root/${driver.driverId}"
        try {
            checked("mkdir -p ${quote(root)} && mkdir ${quote(stage)}", execute)
            val libraries = (listOf(driver.libraryName) + entries.filter {
                it.isRegularFile && it.path.endsWith(".so")
            }.map { it.path }).distinct()
            val verifiedHashes = mutableMapOf<String, String>()
            var expandedSize = 0L
            for (name in listOf("meta.json") + libraries) {
                val path = "$stage/$name"
                checked(": > ${quote(path)}", execute)
                val sourceHash = Sha256()
                var size = 0L
                val header = ByteArray(DriverElfHeader.SIZE)
                var headerSize = 0
                streamFile(name) { base64 ->
                    val bytes = try { Base64.decode(base64) } catch (_: Exception) {
                        throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                    }
                    if (bytes.isEmpty() || bytes.size > CHUNK_SIZE) throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                    size += bytes.size
                    expandedSize += bytes.size
                    if (name == "meta.json") checkMetaSize(size.toInt())
                    if (expandedSize > MAX_ARCHIVE_BYTES) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                    val prefixSize = minOf(bytes.size, header.size - headerSize)
                    bytes.copyInto(header, headerSize, 0, prefixSize)
                    headerSize += prefixSize
                    sourceHash.update(bytes)
                    checked("printf '%s' ${quote(base64)} | base64 -d >> ${quote(path)}", execute)
                }
                if (size == 0L) throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                if (name != "meta.json" && (headerSize < header.size || !DriverElfHeader.isArm64Library(header))) {
                    throw DriverStoreException(DriverArchiveError.UNSUPPORTED_ABI)
                }
                val rootHash = checked("sha256sum ${quote(path)}", execute).stdout.substringBefore(' ').lowercase()
                if (rootHash != sourceHash.hexDigest()) throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                verifiedHashes[name] = rootHash
            }
            return RepositoryMutationGuard.mutate {
                val latest = readIndex(execute)
                val existing = latest.firstOrNull { it.driverId == driver.driverId }
                if (existing != null) {
                    for ((name, expectedHash) in verifiedHashes) {
                        val installedHash = checked("sha256sum ${quote("$final/$name")}", execute)
                            .stdout.substringBefore(' ').lowercase()
                        if (installedHash != expectedHash) throw DriverStoreException(DriverArchiveError.STORAGE_ERROR)
                    }
                    return@mutate DriverImportResult.Accepted(existing.info())
                }
                val occupied = execute("test -e ${quote(final)} || test -L ${quote(final)}")
                if (occupied.errno == 0) throw DriverStoreException(DriverArchiveError.STORAGE_ERROR)
                val record = DriverRecord(driver.driverId, driver.name, driver.libraryName, driver.abi,
                    importedAtEpochMillis, archiveSha256.lowercase())
                val preparedIndex = prepareIndex(latest + record, execute)
                try {
                    checked("if [ -e ${quote(final)} ] || [ -L ${quote(final)} ]; then exit 17; fi; mv ${quote(stage)} ${quote(final)}", execute)
                    try {
                        checked("mv ${quote(preparedIndex)} ${quote(indexPath)}", execute)
                    } catch (e: Exception) {
                        execute("rm -rf ${quote(final)}")
                        throw e
                    }
                    DriverImportResult.Accepted(record.info())
                } finally {
                    execute("rm -f ${quote(preparedIndex)}")
                }
            }
        } catch (e: DriverStoreException) {
            return DriverImportResult.Rejected(e.code)
        } catch (_: Exception) {
            return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
        } finally {
            execute("if [ -d ${quote(stage)} ]; then rm -rf ${quote(stage)}; fi")
        }
    }

    private suspend fun prepareIndex(records: List<DriverRecord>, execute: RootCommand): String {
        val temp = "$root/.index-${nonce()}"
        try {
            checked(": > ${quote(temp)}", execute)
            val bytes = json.encodeToString(records).encodeToByteArray()
            var offset = 0
            transfer(
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
