package io.github.nku100.webui.data

import androidx.compose.runtime.Immutable
import io.github.nku100.webui.platform.PlatformBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Immutable
data class DriverReleaseAsset(
    val repository: String,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val downloadCount: Int,
    val sha256: String? = null,
)

@Immutable
data class DriverRelease(
    val repository: String,
    val tag: String,
    val title: String,
    val publishedAt: String,
    val notes: String,
    val isPrerelease: Boolean,
    val assets: List<DriverReleaseAsset>,
)

object DriverSources {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    val defaults = listOf(
        "StevenMXZ/Adreno-Tools-Drivers",
        "The412Banner/Banners-Turnip",
        "faux123/turnip-faux123-driver",
        "K11MCH1/AdrenoToolsDrivers",
    )

    fun normalize(value: String): String? {
        val input = value.trim().trimEnd('/')
        val path = when {
            input.startsWith("https://github.com/", ignoreCase = true) -> input.substring("https://github.com/".length)
            input.startsWith("http://", ignoreCase = true) || input.contains("://") -> return null
            else -> input
        }
        if ('?' in path || '#' in path || '\\' in path || '\u0000' in path) return null
        val segments = path.split('/')
        val repoSegments = when {
            segments.size == 2 -> segments
            segments.size == 3 && segments[2].equals("releases", ignoreCase = true) -> segments.take(2)
            else -> return null
        }
        val owner = repoSegments[0]
        val repo = repoSegments[1]
        if (!owner.matches(Regex("[A-Za-z0-9-]{1,39}")) ||
            !repo.matches(Regex("[A-Za-z0-9._-]{1,100}")) || repo == "." || repo == "..") return null
        return "$owner/$repo"
    }

    fun identity(repository: String): String? = normalize(repository)?.lowercase()

    fun effective(configured: List<String>?): List<String> = configured?.let(::deduplicate).orEmpty()
        .ifEmpty { if (configured == null) defaults else emptyList() }

    fun add(current: List<String>, value: String): List<String> {
        val repository = normalize(value) ?: return deduplicate(current)
        return deduplicate(current + repository)
    }

    fun addDefaults(current: List<String>): List<String> = deduplicate(current + defaults)

    fun remove(current: List<String>, value: String): List<String> {
        val identity = identity(value) ?: return deduplicate(current)
        return deduplicate(current.filterNot { identity(it) == identity })
    }

    fun releasesApiUrl(repository: String): String? = normalize(repository)?.let {
        "https://api.github.com/repos/$it/releases?per_page=30"
    }

    suspend fun fetchReleases(repository: String): List<DriverRelease> {
        val url = releasesApiUrl(repository) ?: throw IllegalArgumentException("Invalid GitHub repository")
        val command = "if command -v curl >/dev/null 2>&1; then " +
            "curl --fail --location --silent --show-error --connect-timeout 15 --max-time 45 " +
            "--max-filesize 5242880 --header 'Accept: application/vnd.github+json' " +
            "--user-agent 'GPU-Driver-Loader' ${quote(url)}; " +
            "elif command -v wget >/dev/null 2>&1; then wget -q -T 45 -O - ${quote(url)}; " +
            "else exit 127; fi"
        val result = PlatformBridge.exec(command)
        if (result.errno != 0 || !result.stdout.trimStart().startsWith("[")) {
            throw IllegalStateException(result.stderr.ifBlank { "GitHub releases could not be loaded" })
        }
        return parseReleases(repository, result.stdout)
    }

    fun parseReleases(repository: String, content: String): List<DriverRelease> {
        val normalizedRepository = normalize(repository) ?: return emptyList()
        val elements = runCatching { json.parseToJsonElement(content).jsonArray }.getOrNull() ?: return emptyList()
        return elements.mapNotNull { element ->
            runCatching {
                val release = element.jsonObject
                if (release["draft"]?.jsonPrimitive?.content == "true") return@mapNotNull null
                val tag = release["tag_name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val assets = release["assets"]?.jsonArray.orEmpty().mapNotNull { assetElement ->
                    val asset = assetElement.jsonObject
                    val name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                    val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (!isSupportedAssetName(name) || size <= 0 || size > MAX_ARCHIVE_BYTES ||
                        !isDownloadUrl(normalizedRepository, url)) return@mapNotNull null
                    val digest = asset["digest"]?.jsonPrimitive?.content
                        ?.removePrefix("sha256:")
                        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                        ?.lowercase()
                    DriverReleaseAsset(
                        repository = normalizedRepository,
                        name = name,
                        downloadUrl = url,
                        sizeBytes = size,
                        downloadCount = asset["download_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        sha256 = digest,
                    )
                }
                if (assets.isEmpty()) return@mapNotNull null
                DriverRelease(
                    repository = normalizedRepository,
                    tag = tag,
                    title = release["name"]?.jsonPrimitive?.content?.ifBlank { tag } ?: tag,
                    publishedAt = release["published_at"]?.jsonPrimitive?.content.orEmpty(),
                    notes = release["body"]?.jsonPrimitive?.content.orEmpty(),
                    isPrerelease = release["prerelease"]?.jsonPrimitive?.content == "true",
                    assets = assets,
                )
            }.getOrNull()
        }
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun isSupportedAssetName(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) &&
            !name.endsWith("-wayland.zip", ignoreCase = true) &&
            !name.endsWith("-linux.zip", ignoreCase = true)

    fun isTrustedDownloadUrl(repository: String, url: String): Boolean =
        normalize(repository)?.let { isDownloadUrl(it, url) } == true

    private fun isDownloadUrl(repository: String, url: String): Boolean {
        val prefix = "https://github.com/$repository/releases/download/"
        return url.startsWith(prefix, ignoreCase = true) &&
            url.length > prefix.length && '?' !in url && '#' !in url && '\u0000' !in url && '\\' !in url
    }

    private fun deduplicate(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.mapNotNull { normalize(it) }.filter { seen.add(it.lowercase()) }
    }
}
