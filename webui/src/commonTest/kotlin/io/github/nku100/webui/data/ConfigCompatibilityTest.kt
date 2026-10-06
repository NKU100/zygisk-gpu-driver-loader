package io.github.nku100.webui.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigCompatibilityTest {
    @Test
    fun legacyLogSettingsAreIgnoredWithoutLosingBindingsOrPreferences() {
        val legacy = """
            {
              "enabled": false,
              "targetPackages": ["example.active"],
              "themeMode": "DARK",
              "enableBlur": false,
              "packageSettings": {
                "example.active": {
                  "driverId": "sha256:driver-a",
                  "note": "保留备注",
                  "logLevel": "DEBUG",
                  "logTag": "custom-tag",
                  "dumpStackTrace": true
                },
                "example.inactive": {"driverId": "sha256:driver-b", "logLevel": "WARN"}
              }
            }
        """.trimIndent()
        val expected = ModuleConfig(
            enabled = false,
            targetPackages = listOf("example.active"),
            themeMode = "DARK",
            enableBlur = false,
            packageSettings = mapOf(
                "example.active" to PackageSettings(driverId = "sha256:driver-a", note = "保留备注"),
                "example.inactive" to PackageSettings(driverId = "sha256:driver-b"),
            ),
        )

        val decoded = ConfigRepository.decode(legacy)
        assertEquals(expected, decoded)
        val saved = Json.encodeToString(decoded)
        for (removed in listOf("logLevel", "logTag", "dumpStackTrace")) {
            assertFalse(saved.contains("\"$removed\""))
        }
        assertEquals(expected, ConfigRepository.decode(saved))
    }
}
