package io.github.nku100.webui.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
    var toastCallback: ((String) -> Unit)? = null

    actual suspend fun listDrivers(): List<DriverInfo> = DriverRepository.listStored()

    actual suspend fun deleteDriver(driverId: String): DriverDeleteResult = DriverRepository.deleteStored(driverId)

    /** Escape a value for safe use as a shell argument (wraps in single quotes). */
    private fun shellEscape(arg: String): String =
        "'${arg.replace("'", "'\\''")}'"

    actual suspend fun exec(command: String): ShellResult = executeRootCommand(command, 60)

    actual suspend fun execReleaseRequest(command: String): ShellResult = executeRootCommand(command, 60)

    actual suspend fun execDriverImport(command: String): ShellResult = executeRootCommand(command, 5 * 60)

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    private suspend fun executeRootCommand(command: String, timeoutSeconds: Long): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            try {
                coroutineScope {
                    val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
                    val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() } }
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        ShellResult(-1, stdout.await(), "Root command timed out: ${stderr.await()}")
                    } else {
                        ShellResult(process.exitValue(), stdout.await(), stderr.await())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShellResult(-1, "", e.message ?: "Unknown error")
            } finally {
                process.destroy()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Failed to execute su")
        }
    }

    actual fun toast(message: String) {
        toastCallback?.invoke(message)
    }

    actual suspend fun listPackages(): List<PackageInfo> = withContext(Dispatchers.IO) {
        val packages = RootAccess.packages(userId = android.os.Process.myUid() / 100000)
        val pm = appContext?.packageManager ?: return@withContext packages
        packages.map { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg.packageName, 0)
                pkg.copy(label = info.loadLabel(pm).toString(), iconModel = info)
            } catch (_: Exception) {
                pkg
            }
        }.sortedBy { it.label.lowercase() }
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
