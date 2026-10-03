package io.github.nku100.webui.platform

import kotlinx.coroutines.await
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise

// JS interop: bridge to KernelSU's ksu object (v3.0.2 API)
// Official API: https://www.npmjs.com/package/kernelsu

// exec uses callback pattern: ksu.exec(command, options, callbackName)
// KernelSU will call window[callbackName](errno, stdout, stderr)
@JsFun("""
(command, options) => {
    return new Promise((resolve, reject) => {
        const callbackName = '__ksu_exec_' + Date.now() + '_' + Math.random().toString(36).slice(2);
        window[callbackName] = (errno, stdout, stderr) => {
            delete window[callbackName];
            resolve({ errno: errno, stdout: stdout, stderr: stderr });
        };
        try {
            ksu.exec(command, JSON.stringify(options), callbackName);
        } catch (e) {
            delete window[callbackName];
            reject(e);
        }
    });
}
""")
private external fun ksuExecJs(command: String, options: JsAny): Promise<JsAny>

// Empty JS object for default exec options
@JsFun("() => ({})")
private external fun emptyJsObject(): JsAny

// toast: ksu.toast(message) — synchronous
@JsFun("(msg) => ksu.toast(msg)")
private external fun ksuToastJs(message: String)

// moduleInfo: ksu.moduleInfo() — synchronous, returns string
@JsFun("() => ksu.moduleInfo()")
private external fun ksuModuleInfoJs(): JsString

// getPackagesInfo: ksu.getPackagesInfo(packages) — synchronous, returns JSON string
@JsFun("(pkgs) => { try { return ksu.getPackagesInfo(pkgs); } catch(e) { return '[]'; } }")
private external fun ksuGetPackagesInfoJs(packages: String): String

// fullScreen: ksu.fullScreen(isFullScreen)
@JsFun("(v) => ksu.fullScreen(v)")
private external fun ksuFullScreenJs(isFullScreen: Boolean)

// enableEdgeToEdge: ksu.enableEdgeToEdge(enable)
@JsFun("(v) => ksu.enableEdgeToEdge(v)")
private external fun ksuEnableEdgeToEdgeJs(enable: Boolean)

// exit: ksu.exit()
@JsFun("() => ksu.exit()")
private external fun ksuExitJs()

// Utility helpers
@JsFun("(result) => result.errno")
private external fun getErrno(result: JsAny): Int

@JsFun("(result) => result.stdout")
private external fun getStdout(result: JsAny): String

@JsFun("(result) => result.stderr")
private external fun getStderr(result: JsAny): String

@JsFun("() => Date.now()")
private external fun currentTimeMillisJs(): Double

actual val isAndroidPlatform: Boolean = false

@JsFun("() => typeof window !== 'undefined' && typeof window.ksu !== 'undefined' && window.ksu != null")
private external fun hasKsuApiJs(): Boolean

actual fun hasPlatformApi(): Boolean = hasKsuApiJs()

@JsFun("""(url) => {
    if (typeof window.ksu !== 'undefined' && window.ksu.exec) {
        if (window.ksu.toast) window.ksu.toast('Redirecting to ' + url);
        setTimeout(function() {
            var cb = '__open_url_' + Date.now() + '_' + Math.random().toString(36).slice(2);
            window[cb] = function(errno, stdout, stderr) {
                delete window[cb];
                if (errno !== 0) window.open(url, '_blank');
            };
            try {
                var escaped = url.replace(/'/g, "'\\\\''");
                window.ksu.exec("am start -a android.intent.action.VIEW -d '" + escaped + "'", '{}', cb);
            }
            catch(e) { delete window[cb]; window.open(url, '_blank'); }
        }, 100);
    } else {
        window.open(url, '_blank');
    }
}""")
private external fun openUrlJs(url: String)

actual fun openUrl(url: String) = openUrlJs(url)

actual object PlatformBridge {
    actual suspend fun execDriverImport(command: String): ShellResult = exec(command)

    actual fun currentTimeMillis(): Long = currentTimeMillisJs().toLong()

    actual suspend fun listDrivers(): List<DriverInfo> = DriverRepository.listStored()

    actual suspend fun deleteDriver(driverId: String): DriverDeleteResult = DriverRepository.deleteStored(driverId)
    actual suspend fun exec(command: String): ShellResult {
        val result = ksuExecJs(command, emptyJsObject()).await<JsAny>()
        return ShellResult(
            errno = getErrno(result),
            stdout = getStdout(result),
            stderr = getStderr(result),
        )
    }

    actual fun toast(message: String) {
        ksuToastJs(message)
    }

    actual suspend fun listPackages(): List<PackageInfo> {
        val packages = RootAccess.packages()
        val labels = mutableMapOf<String, String>()
        try {
            val infoJson = ksuGetPackagesInfoJs(Json.encodeToString(packages.map { it.packageName }))
            for (element in Json.parseToJsonElement(infoJson).jsonArray) {
                val obj = element.jsonObject
                val name = obj["packageName"]?.jsonPrimitive?.content ?: continue
                val label = obj["appLabel"]?.jsonPrimitive?.content ?: continue
                if (label.isNotBlank()) labels[name] = label
            }
        } catch (_: Exception) {
            // Some WebUI hosts cannot supply labels; the root inventory remains authoritative.
        }
        return packages.map { it.copy(label = labels[it.packageName] ?: it.packageName,
            iconModel = "ksu://icon/${it.packageName}") }
    }

    actual suspend fun readFile(path: String): String {
        val escapedPath = path.replace("'", "'\\''")
        val result = exec("cat '$escapedPath' 2>/dev/null || echo ''")
        return result.stdout
    }

    actual suspend fun writeFile(path: String, content: String) {
        val escapedPath = path.replace("'", "'\\''")
        val escaped = content.replace("'", "'\\''")
        exec("echo '${escaped}' > '${escapedPath}'")
    }
}
