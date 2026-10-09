package io.github.nku100.webui.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriverSourcesTest {
    @Test
    fun normalizesGitHubRepositoryUrlsAndRejectsNonRepositoryPaths() {
        assertEquals("The412Banner/Banners-Turnip", DriverSources.normalize(" https://github.com/The412Banner/Banners-Turnip/releases/ "))
        assertEquals("OWNER/Repo", DriverSources.normalize("OWNER/Repo"))
        assertEquals("OWNER/Repo", DriverSources.normalize("HTTPS://GITHUB.COM/OWNER/Repo"))
        assertNull(DriverSources.normalize("https://example.com/owner/repo"))
        assertNull(DriverSources.normalize("https://github.com/owner/repo/issues"))
        assertNull(DriverSources.normalize("https://github.com/owner/repo?tab=releases"))
    }

    @Test
    fun addsAndRestoresDefaultsWithoutDuplicates() {
        val custom = "owner/repo"
        val withCustom = DriverSources.add(emptyList(), custom)
        val merged = DriverSources.addDefaults(withCustom + "the412banner/banners-turnip")

        assertEquals(5, merged.size)
        assertEquals(1, merged.count { it.equals("The412Banner/Banners-Turnip", ignoreCase = true) })
        assertTrue(merged.any { it.equals(custom, ignoreCase = true) })
    }

    @Test
    fun removesDefaultAndCustomSourcesByCanonicalIdentity() {
        val sources = DriverSources.defaults + "owner/repo"

        val removed = DriverSources.remove(sources, "https://github.com/K11MCH1/AdrenoToolsDrivers/releases")

        assertFalse(removed.any { it.equals("K11MCH1/AdrenoToolsDrivers", ignoreCase = true) })
        assertTrue(removed.contains("owner/repo"))
        val restored = DriverSources.addDefaults(removed)
        assertEquals(5, restored.size)
        assertTrue(DriverSources.defaults.all { default -> restored.any { it.equals(default, ignoreCase = true) } })
        assertTrue(restored.contains("owner/repo"))
    }

    @Test
    fun releaseParserShowsOnlyValidAndroidZipAssetsAndSkipsDrafts() {
        val json = """[
          {"tag_name":"v1","name":"Release one","draft":false,"prerelease":false,"published_at":"2026-10-01T00:00:00Z","body":"notes","assets":[
            {"name":"Turnip.zip","size":123,"browser_download_url":"https://github.com/owner/repo/releases/download/v1/Turnip.zip"},
            {"name":"Turnip-Wayland.zip","size":123,"browser_download_url":"https://github.com/owner/repo/releases/download/v1/Turnip-Wayland.zip"},
            {"name":"wrong.zip","size":123,"browser_download_url":"https://example.com/wrong.zip"},
            {"name":"large.zip","size":536870913,"browser_download_url":"https://github.com/owner/repo/releases/download/v1/large.zip"}
          ]},
          {"tag_name":"draft","draft":true,"assets":[]}
        ]"""

        val releases = DriverSources.parseReleases("owner/repo", json)

        assertEquals(1, releases.size)
        assertEquals("v1", releases.single().tag)
        assertEquals(listOf("Turnip.zip"), releases.single().assets.map { it.name })
    }
}
