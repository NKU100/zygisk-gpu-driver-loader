package io.github.nku100.webui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootAccessTest {
    @Test
    fun rootIdentityRequiresSuccessfulUidProbe() {
        assertFalse(RootAccess.parseEnvironment(ShellResult(1, "ROOT_UID=0\n31:MAGISKSU", "denied")).available)
        assertFalse(RootAccess.parseEnvironment(ShellResult(0, "31:MAGISKSU", "")).available)
        for ((version, implementation) in listOf(
            "31.0:MAGISKSU" to RootImplementation.MAGISK,
            "v3.0:KernelSU" to RootImplementation.KERNEL_SU,
            "11000:APatch" to RootImplementation.APATCH,
            "custom-su" to RootImplementation.UNKNOWN,
        )) {
            val environment = RootAccess.parseEnvironment(ShellResult(0, "ROOT_UID=0\n$version\n", ""))
            assertTrue(environment.available)
            assertEquals(implementation, environment.implementation)
        }
    }

    @Test
    fun inventoryPreservesSystemAppsAndDeduplicatesPackages() {
        val packages = RootAccess.parsePackages(ShellResult(0,
            "package:example.user\npackage:example.system\npackage:example.user\n__SYSTEM_PACKAGES__\npackage:example.system\n", ""))
        assertEquals(listOf("example.system", "example.user"), packages.map { it.packageName })
        assertTrue(packages[0].isSystemApp)
        assertFalse(packages[1].isSystemApp)
    }

    @Test
    fun deniedPartialAndEmptyQueriesAreFailuresNotEmptyLists() {
        for (result in listOf(
            ShellResult(1, "", "denied"),
            ShellResult(1, "package:example.user\n__SYSTEM_PACKAGES__\n", "failed"),
            ShellResult(0, "package:example.user\n", ""),
            ShellResult(0, "__SYSTEM_PACKAGES__\n", ""),
        )) assertFailsWith<IllegalStateException> { RootAccess.parsePackages(result) }
    }
}
