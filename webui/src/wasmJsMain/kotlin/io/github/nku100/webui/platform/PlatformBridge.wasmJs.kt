package io.github.nku100.webui.platform

import kotlinx.coroutines.await
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverArchivePolicy
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverFileInfo
import io.github.nku100.webui.data.DriverImportResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverRepository
import io.github.nku100.webui.data.DriverStoreException
import io.github.nku100.webui.data.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise
import kotlin.io.encoding.Base64

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

// listPackages: ksu.listPackages(type) — synchronous, returns JSON string
@JsFun("(type) => { try { return ksu.listPackages(type); } catch(e) { return '[]'; } }")
private external fun ksuListPackagesJs(type: String): String

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

@JsFun("(arr) => arr.length")
private external fun arrayLength(arr: JsAny): Int

@JsFun("(arr, i) => arr[i]")
private external fun arrayGet(arr: JsAny, index: Int): JsAny

@JsFun("(obj, key) => obj[key] || ''")
private external fun objGetString(obj: JsAny, key: String): String

@JsFun("""() => new Promise(resolve => {
    const input = document.createElement('input');
    input.type = 'file'; input.accept = '.zip,application/zip';
    input.style.display = 'none'; document.body.appendChild(input);
    let settled = false;
    const finish = file => { if (settled) return; settled = true; input.remove(); resolve(file || {}); };
    input.onchange = () => finish(input.files && input.files[0]);
    window.addEventListener('focus', () => setTimeout(() => {
        if (!input.files || !input.files.length) finish(null);
    }, 500), {once:true});
    input.click();
})""")
private external fun pickDriverFileJs(): Promise<JsAny>

@JsFun("(file) => file.size || 0")
private external fun fileSizeJs(file: JsAny): Double

@JsFun("""async (file, start, count) => {
    const bytes = new Uint8Array(await file.slice(start, start + count).arrayBuffer());
    let s = ''; for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
}""")
private external fun fileChunkJs(file: JsAny, start: Double, count: Int): Promise<JsString>

@JsFun("""async (file) => {
    const tailStart = Math.max(0, file.size - 65557);
    const tail = new DataView(await file.slice(tailStart).arrayBuffer());
    const u16 = (v, p) => v.getUint16(p, true), u32 = (v, p) => v.getUint32(p, true);
    let eocd = -1;
    for (let p = tail.byteLength - 22; p >= 0; p--) {
        if (u32(tail, p) === 0x06054b50) { eocd = p; break; }
    }
    if (eocd < 0) throw Error('INVALID_ZIP');
    if (u16(tail, eocd + 4) !== 0 || u16(tail, eocd + 6) !== 0 ||
        u16(tail, eocd + 8) !== u16(tail, eocd + 10)) throw Error('INVALID_ZIP');
    const count = u16(tail, eocd + 10), size = u32(tail, eocd + 12), offset = u32(tail, eocd + 16);
    if (count > 4096 || count === 65535 || offset === 0xffffffff || size > 4 * 1024 * 1024 || offset + size > file.size) throw Error('INVALID_ZIP');
    const central = new DataView(await file.slice(offset, offset + size).arrayBuffer());
    const decoder = new TextDecoder('utf-8', {fatal:true});
    const entries = []; let p = 0;
    for (let i = 0; i < count; i++) {
        if (p + 46 > central.byteLength || u32(central, p) !== 0x02014b50) throw Error('INVALID_ZIP');
        const flags = u16(central, p + 8);
        if (flags & 1) throw Error('INVALID_ZIP');
        const nameLength = u16(central, p + 28), extra = u16(central, p + 30), comment = u16(central, p + 32);
        if (p + 46 + nameLength + extra + comment > central.byteLength) throw Error('INVALID_ZIP');
        const path = decoder.decode(new Uint8Array(central.buffer, p + 46, nameLength));
        const mode = u32(central, p + 38) >>> 16;
        entries.push({path, regular: !path.endsWith('/') && (mode & 0xf000) !== 0xa000,
            symlink: (mode & 0xf000) === 0xa000, method: u16(central, p + 10),
            compressedSize: u32(central, p + 20), uncompressedSize: u32(central, p + 24),
            crc: u32(central, p + 16), localOffset: u32(central, p + 42)});
        p += 46 + nameLength + extra + comment;
    }
    return JSON.stringify(entries);
}""")
private external fun zipManifestJs(file: JsAny): Promise<JsString>

@JsFun("""async (file, offset, compressedSize, method, name) => {
    if (method !== 0 && method !== 8) throw Error('INVALID_ZIP');
    const header = new DataView(await file.slice(offset, offset + 30).arrayBuffer());
    if (header.byteLength !== 30 || header.getUint32(0, true) !== 0x04034b50) throw Error('INVALID_ZIP');
    const nameLength = header.getUint16(26, true), extraLength = header.getUint16(28, true);
    const localName = new TextDecoder('utf-8', {fatal:true}).decode(await file.slice(offset + 30, offset + 30 + nameLength).arrayBuffer());
    if (localName !== name) throw Error('INVALID_ZIP');
    const start = offset + 30 + nameLength + extraLength;
    if (start + compressedSize > file.size) throw Error('INVALID_ZIP');
    const stream = file.slice(start, start + compressedSize).stream();
    const reader = (method === 8 ? stream.pipeThrough(new DecompressionStream('deflate-raw')) : stream).getReader();
    return {reader, pending:null, position:0, crc:0xffffffff, length:0};
}""")
private external fun openEntryReaderJs(file: JsAny, offset: Double, compressedSize: Double, method: Int, name: String): Promise<JsAny>

@JsFun("""async (state) => {
    if (!state.pending || state.position >= state.pending.length) {
        const part = await state.reader.read();
        if (part.done) return {done:true, crc:(state.crc ^ 0xffffffff) >>> 0, length:state.length};
        if (part.value.length === 0) return {done:false, base64:''};
        state.pending = part.value; state.position = 0;
    }
    const end = Math.min(state.position + 12288, state.pending.length);
    const bytes = state.pending.subarray(state.position, end); state.position = end;
    for (const b of bytes) {
        state.crc ^= b;
        for (let k = 0; k < 8; k++) state.crc = (state.crc >>> 1) ^ ((state.crc & 1) ? 0xedb88320 : 0);
    }
    state.length += bytes.length;
    let s = ''; for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return {done:false, base64:btoa(s)};
}""")
private external fun nextEntryChunkJs(reader: JsAny): Promise<JsAny>

@JsFun("(value) => value.done === true")
private external fun entryDoneJs(value: JsAny): Boolean

@JsFun("(value) => value.crc >>> 0")
private external fun entryCrcJs(value: JsAny): Double

@JsFun("(value) => value.length || 0")
private external fun entryLengthJs(value: JsAny): Double

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
    actual suspend fun importDriverZip(): DriverImportResult {
        if (!hasPlatformApi()) return DriverImportResult.Rejected(DriverArchiveError.STORAGE_ERROR)
        return try {
            val file = pickDriverFileJs().await<JsAny>()
            val size = fileSizeJs(file).toLong()
            if (size == 0L) return DriverImportResult.Rejected(DriverArchiveError.CANCELLED)
            DriverRepository.checkArchiveSize(size)
            val hash = Sha256()
            var offset = 0L
            while (offset < size) {
                val count = minOf(DriverRepository.CHUNK_SIZE.toLong(), size - offset).toInt()
                val bytes = Base64.decode(fileChunkJs(file, offset.toDouble(), count).await<JsString>().toString())
                if (bytes.size != count) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                hash.update(bytes)
                offset += count
            }
            val archiveHash = hash.hexDigest()
            val manifest = Json.parseToJsonElement(zipManifestJs(file).await<JsString>().toString()).jsonArray
            val entries = manifest.map { item ->
                val obj = item.jsonObject
                DriverFileInfo(obj.getValue("path").jsonPrimitive.content,
                    obj.getValue("regular").jsonPrimitive.content.toBoolean(),
                    obj.getValue("symlink").jsonPrimitive.content.toBoolean())
            }
            if (entries.any { it.isSymbolicLink }) return DriverImportResult.Rejected(DriverArchiveError.SYMBOLIC_LINK)
            if (entries.none { it.path == "meta.json" }) return DriverImportResult.Rejected(DriverArchiveError.MISSING_META_JSON)
            suspend fun streamEntry(name: String, append: suspend (String) -> Unit) {
                val item = manifest.singleOrNull { it.jsonObject["path"]?.jsonPrimitive?.content == name }
                    ?: throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                val obj = item.jsonObject
                val expectedSize = obj.getValue("uncompressedSize").jsonPrimitive.content.toLong()
                if (expectedSize > 512L * 1024 * 1024) throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                val reader = openEntryReaderJs(file, obj.getValue("localOffset").jsonPrimitive.content.toDouble(),
                    obj.getValue("compressedSize").jsonPrimitive.content.toDouble(),
                    obj.getValue("method").jsonPrimitive.content.toInt(), name).await<JsAny>()
                while (true) {
                    val part = nextEntryChunkJs(reader).await<JsAny>()
                    if (entryDoneJs(part)) {
                        if (entryLengthJs(part).toLong() != expectedSize ||
                            entryCrcJs(part).toLong() != obj.getValue("crc").jsonPrimitive.content.toLong()) {
                            throw DriverStoreException(DriverArchiveError.INVALID_ZIP)
                        }
                        break
                    }
                    val chunk = objGetString(part, "base64")
                    if (chunk.isNotEmpty()) append(chunk)
                }
            }
            val metaBytes = mutableListOf<Byte>()
            streamEntry("meta.json") { chunk ->
                metaBytes += Base64.decode(chunk).toList()
                DriverRepository.checkMetaSize(metaBytes.size)
            }
            val meta = DriverRepository.parseMeta(metaBytes.toByteArray().decodeToString())
            val validation = DriverArchivePolicy.validate(archiveHash, entries, meta)
            if (validation !is DriverImportResult.Accepted) return validation
            DriverRepository.publish(archiveHash, entries, meta, currentTimeMillisJs().toLong()) { name, append ->
                streamEntry(name, append)
            }
        } catch (e: DriverStoreException) {
            DriverImportResult.Rejected(e.code)
        } catch (_: Exception) {
            DriverImportResult.Rejected(DriverArchiveError.INVALID_ZIP)
        }
    }

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
        // Try ksu.listPackages API first (KernelSU manager)
        try {
            val userJson = ksuListPackagesJs("user")
            val systemJson = ksuListPackagesJs("system")
            val userPkgs = if (userJson.isNotBlank() && userJson != "[]")
                Json.parseToJsonElement(userJson).jsonArray.map { it.jsonPrimitive.content }
            else emptyList()
            val systemPkgs = if (systemJson.isNotBlank() && systemJson != "[]")
                Json.parseToJsonElement(systemJson).jsonArray.map { it.jsonPrimitive.content }
            else emptyList()

            if (userPkgs.isNotEmpty() || systemPkgs.isNotEmpty()) {
                val allPkgs = userPkgs + systemPkgs
                val systemSet = systemPkgs.toSet()

                // Try getPackagesInfo for labels
                try {
                    val infoJson = ksuGetPackagesInfoJs(Json.encodeToString(allPkgs))
                    if (infoJson.isNotBlank() && infoJson != "[]") {
                        val infoArray = Json.parseToJsonElement(infoJson).jsonArray
                        return infoArray.map { element ->
                            val obj = element.jsonObject
                            val pkgName = obj["packageName"]?.jsonPrimitive?.content ?: ""
                            val label = obj["appLabel"]?.jsonPrimitive?.content ?: pkgName
                            PackageInfo(
                                packageName = pkgName,
                                label = label.ifBlank { pkgName },
                                iconModel = "ksu://icon/$pkgName",
                                isSystemApp = pkgName in systemSet,
                            )
                        }
                    }
                } catch (_: Exception) { /* getPackagesInfo not available */ }

                return allPkgs.map {
                    PackageInfo(
                        packageName = it,
                        iconModel = "ksu://icon/$it",
                        isSystemApp = it in systemSet,
                    )
                }
            }
        } catch (_: Exception) { /* listPackages not available (e.g. KsuWebUIStandalone) */ }

        // Fallback: use exec("pm list packages -3")
        return try {
            val result = exec("pm list packages -3")
            if (result.errno != 0) return emptyList()
            result.stdout.lines()
                .filter { it.startsWith("package:") }
                .map { PackageInfo(packageName = it.removePrefix("package:").trim()) }
                .filter { it.packageName.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
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
