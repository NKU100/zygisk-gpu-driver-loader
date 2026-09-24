package io.github.nku100.webui.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverArchivePolicy
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverFileInfo
import io.github.nku100.webui.data.DriverImportResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverRepository
import io.github.nku100.webui.data.DriverStoreException
import io.github.nku100.webui.data.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

actual val isAndroidPlatform: Boolean = true

actual fun hasPlatformApi(): Boolean = true

actual fun openUrl(url: String) {
    val context = PlatformBridge.appContext ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

actual object PlatformBridge {
    // Set by the Activity on creation
    var appContext: Context? = null
    var appActivity: ComponentActivity? = null
    var toastCallback: ((String) -> Unit)? = null

    actual suspend fun importDriverZip(): DriverImportResult {
        val activity = appActivity ?: return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
        val uri = try { withContext(Dispatchers.Main) { suspendCancellableCoroutine<Uri?> { continuation ->
            val key = "driver-zip-${System.nanoTime()}"
            lateinit var launcher: ActivityResultLauncher<Array<String>>
            launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.OpenDocument()) { picked ->
                launcher.unregister()
                if (continuation.isActive) continuation.resume(picked)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(arrayOf("application/zip"))
        } } } catch (_: Exception) {
            return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
        } ?: return DriverImportResult.Rejected(DriverArchiveError.CANCELLED)
        return withContext(Dispatchers.IO) {
            val context = appContext ?: return@withContext DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
            val archive = try { File.createTempFile("driver-", ".zip", context.cacheDir) } catch (_: Exception) {
                return@withContext DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
            }
            try {
                val digest = Sha256()
                var size = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(DriverRepository.CHUNK_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                            size += count
                            DriverRepository.checkArchiveSize(size)
                            digest.update(buffer, count)
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                DriverRepository.checkArchiveSize(size)
                val archiveHash = digest.hexDigest()
                val centralEntries = readCentralEntries(archive)
                val symbolicLinks = centralEntries.filter { it.second }.map { it.first }.toSet()
                val entries = mutableListOf<DriverFileInfo>()
                var metadata: Map<String, String>? = null
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    var total = 0L
                    var index = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (index >= centralEntries.size || entry.name != centralEntries[index].first) {
                            throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                        }
                        index++
                        entries += DriverFileInfo(entry.name, !entry.isDirectory && entry.name !in symbolicLinks,
                            entry.name in symbolicLinks)
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(DriverRepository.CHUNK_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 512L * 1024 * 1024) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                            if (entry.name == "meta.json") {
                                DriverRepository.checkMetaSize(bytes.size() + count)
                                bytes.write(buffer, 0, count)
                            }
                        }
                        if (entry.name == "meta.json") metadata = DriverRepository.parseMeta(bytes.toString(Charsets.UTF_8.name()))
                        zip.closeEntry()
                    }
                    if (index != centralEntries.size) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                }
                val validated = DriverArchivePolicy.validate(archiveHash, entries, metadata ?: emptyMap())
                if (validated !is DriverImportResult.Accepted) return@withContext validated
                DriverRepository.publish(archiveHash, entries, metadata ?: emptyMap(), System.currentTimeMillis()) { name, append ->
                    ZipInputStream(archive.inputStream().buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name == name) {
                                DriverRepository.transfer({ buffer -> zip.read(buffer) }, append)
                                zip.closeEntry()
                                return@use
                            }
                            zip.closeEntry()
                        }
                        throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
                    }
                }
            } catch (e: DriverStoreException) {
                DriverImportResult.Rejected(e.code)
            } catch (_: Exception) {
                DriverImportResult.Rejected(DriverArchiveError.INVALID_ZIP)
            } finally {
                archive.delete()
            }
        }
    }

    actual suspend fun listDrivers(): List<DriverInfo> = DriverRepository.listStored()

    actual suspend fun deleteDriver(driverId: String): DriverDeleteResult = DriverRepository.deleteStored(driverId)

    private fun readCentralEntries(file: File): List<Pair<String, Boolean>> {
        val entries = mutableListOf<Triple<Long, String, Boolean>>()
        RandomAccessFile(file, "r").use { zip ->
            val tailSize = minOf(zip.length(), 65557L).toInt()
            val tail = ByteArray(tailSize)
            zip.seek(zip.length() - tailSize)
            zip.readFully(tail)
            var end = tailSize - 22
            while (end >= 0 && !(tail[end] == 0x50.toByte() && tail[end + 1] == 0x4b.toByte() &&
                    tail[end + 2] == 0x05.toByte() && tail[end + 3] == 0x06.toByte())) end--
            if (end < 0) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 ||
                u16(tail, end + 8) != u16(tail, end + 10)) {
                throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
            }
            val count = u16(tail, end + 10)
            val centralSize = u32(tail, end + 12)
            val offset = u32(tail, end + 16)
            if (count == 65535 || offset == 0xffffffffL || count > 4096 ||
                centralSize > 4L * 1024 * 1024 || offset + centralSize > zip.length()) {
                throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
            }
            zip.seek(offset)
            repeat(count) {
                val header = ByteArray(46)
                zip.readFully(header)
                if (u32(header, 0) != 0x02014b50L) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                val nameLength = u16(header, 28)
                val extraLength = u16(header, 30)
                val commentLength = u16(header, 32)
                val nameBytes = ByteArray(nameLength)
                zip.readFully(nameBytes)
                val name = nameBytes.toString(Charsets.UTF_8)
                val mode = (u32(header, 38) ushr 16).toInt()
                entries += Triple(u32(header, 42), name, mode and 0xf000 == 0xa000)
                zip.skipBytes(extraLength + commentLength)
            }
        }
        return entries.sortedBy { it.first }.map { it.second to it.third }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    /** Escape a value for safe use as a shell argument (wraps in single quotes). */
    private fun shellEscape(arg: String): String =
        "'${arg.replace("'", "'\\''")}'"

    actual suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            try {
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val errno = process.waitFor()
                ShellResult(errno, stdout, stderr)
            } catch (e: Exception) {
                ShellResult(-1, "", e.message ?: "Unknown error")
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Failed to execute su")
        }
    }

    actual fun toast(message: String) {
        toastCallback?.invoke(message)
    }

    actual suspend fun listPackages(): List<PackageInfo> = withContext(Dispatchers.IO) {
        val ctx = appContext
        if (ctx != null) {
            // Use PackageManager for label + icon
            val pm = ctx.packageManager
            pm.getInstalledApplications(0)
                .map { info ->
                    PackageInfo(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        iconModel = info,
                        isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
        } else {
            // Fallback: shell command
            val result = exec("pm list packages")
            if (result.errno != 0) return@withContext emptyList()
            result.stdout.lines()
                .filter { it.startsWith("package:") }
                .map { PackageInfo(packageName = it.removePrefix("package:").trim()) }
        }
    }

    actual suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        try {
            val result = exec("cat ${shellEscape(path)}")
            if (result.errno == 0) result.stdout else ""
        } catch (_: Exception) { "" }
    }

    actual suspend fun writeFile(path: String, content: String) {
        withContext(Dispatchers.IO) {
            val escaped = content.replace("'", "'\\''")
            exec("echo '${escaped}' > ${shellEscape(path)}")
        }
    }
}
