package io.github.nku100.webui.data

import io.github.nku100.webui.ModuleInfo
import io.github.nku100.webui.platform.PlatformBridge
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Repository that manages reading/writing module configuration.
 * Config is stored as JSON at the module's data directory.
 * Path is auto-generated from moduleId in module.gradle.kts.
 */
object ConfigRepository {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    var configPath: String = ModuleInfo.CONFIG_PATH

    suspend fun load(): ModuleConfig {
        return try {
            val content = PlatformBridge.readFile(configPath)
            if (content.isBlank()) ModuleConfig() else json.decodeFromString(content)
        } catch (_: Exception) {
            ModuleConfig()
        }
    }

    suspend fun save(config: ModuleConfig) = saveWith(config, PlatformBridge::exec)

    internal suspend fun saveWith(config: ModuleConfig, execute: RootCommand, path: String? = null) {
        RepositoryMutationGuard.mutate {
            val target = path ?: configPath
            val temporary = "$target.tmp-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"
            suspend fun checked(command: String) {
                val result = execute(command)
                if (result.errno != 0) {
                    throw IllegalStateException("Failed to save config: ${result.stderr.ifBlank { "errno=${result.errno}" }}")
                }
            }
            try {
                checked(": > ${quote(temporary)}")
                val bytes = json.encodeToString(ModuleConfig.serializer(), config).encodeToByteArray()
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
                    appendBase64 = { chunk -> checked("printf '%s' ${quote(chunk)} | base64 -d >> ${quote(temporary)}") },
                )
                checked("mv ${quote(temporary)} ${quote(target)}")
            } finally {
                execute("rm -f ${quote(temporary)}")
            }
        }
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
