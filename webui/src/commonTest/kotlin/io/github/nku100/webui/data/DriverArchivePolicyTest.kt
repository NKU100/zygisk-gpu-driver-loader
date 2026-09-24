package io.github.nku100.webui.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DriverArchivePolicyTest {
    private val validEntries = listOf(
        DriverFileInfo("meta.json", isRegularFile = true),
        DriverFileInfo("libVkDriver.so", isRegularFile = true),
    )
    private val validMetadata = mapOf(
        "name" to "Example Driver",
        "libraryName" to "libVkDriver.so",
        "abi" to DriverArchivePolicy.SUPPORTED_ABI,
    )

    @Test
    fun acceptsMetadataAndArm64LibraryAtArchiveRoot() {
        val accepted = assertIs<DriverImportResult.Accepted>(validate())
        assertEquals("Example Driver", accepted.driver.name)
        assertEquals("libVkDriver.so", accepted.driver.libraryName)
        assertEquals("arm64-v8a", accepted.driver.abi)
        assertEquals("${"a".repeat(64)}:arm64-v8a:6c6962566b4472697665722e736f", accepted.driver.driverId)
    }

    @Test
    fun rejectsAbsoluteEntryPath() = assertRejected(
        validEntries + DriverFileInfo("/outside", isRegularFile = true),
        validMetadata,
        DriverArchiveError.INVALID_ENTRY_PATH,
    )

    @Test
    fun rejectsDriveQualifiedAbsoluteEntryPath() = assertRejected(
        validEntries + DriverFileInfo("C:/outside", isRegularFile = true),
        validMetadata,
        DriverArchiveError.INVALID_ENTRY_PATH,
    )

    @Test
    fun rejectsWindowsRootedEntryPath() = assertRejected(
        validEntries + DriverFileInfo("\\\\server\\share\\outside", isRegularFile = true),
        validMetadata,
        DriverArchiveError.INVALID_ENTRY_PATH,
    )

    @Test
    fun rejectsTraversalEntryPath() = assertRejected(
        validEntries + DriverFileInfo("../outside", isRegularFile = true),
        validMetadata,
        DriverArchiveError.INVALID_ENTRY_PATH,
    )

    @Test
    fun rejectsSymbolicLinkEntry() = assertRejected(
        listOf(validEntries[0], DriverFileInfo("libVkDriver.so", isRegularFile = false, isSymbolicLink = true)),
        validMetadata,
        DriverArchiveError.SYMBOLIC_LINK,
    )

    @Test
    fun rejectsMissingMetadataFile() = assertRejected(
        listOf(validEntries[1]), validMetadata, DriverArchiveError.MISSING_META_JSON,
    )

    @Test
    fun rejectsMissingLibraryName() = assertRejected(
        validEntries, validMetadata - "libraryName", DriverArchiveError.MISSING_LIBRARY_NAME,
    )

    @Test
    fun rejectsMetadataOnlyArchiveWhenLibraryNameIsMetaJson() = assertRejected(
        listOf(validEntries[0]),
        validMetadata + ("libraryName" to "meta.json"),
        DriverArchiveError.LIBRARY_NAME_MISMATCH,
    )

    @Test
    fun rejectsMetadataOnlyArchiveWhenLibraryNameAliasesMetaJson() = assertRejected(
        listOf(validEntries[0]),
        validMetadata + ("libraryName" to "./meta.json"),
        DriverArchiveError.LIBRARY_NAME_MISMATCH,
    )

    @Test
    fun rejectsDuplicateLibraryNames() = assertRejected(
        validEntries + DriverFileInfo("another/libVkDriver.so", isRegularFile = true),
        validMetadata,
        DriverArchiveError.DUPLICATE_LIBRARY_NAME,
    )

    @Test
    fun rejectsLibraryOutsideArchiveRoot() = assertRejected(
        listOf(validEntries[0], DriverFileInfo("nested/libVkDriver.so", isRegularFile = true)),
        validMetadata,
        DriverArchiveError.LIBRARY_OUTSIDE_ROOT,
    )

    @Test
    fun rejectsNonArm64Abi() = assertRejected(
        validEntries, validMetadata + ("abi" to "armeabi-v7a"), DriverArchiveError.UNSUPPORTED_ABI,
    )

    @Test
    fun packageSettingsWithoutDriverIdUseSystemDriver() {
        val settings = Json.decodeFromString<PackageSettings>("{}").copy()
        assertEquals("", settings.driverId)
    }

    @Test
    fun packageSettingsWithEmptyDriverIdUseSystemDriver() {
        val settings = Json.decodeFromString<PackageSettings>("""{"driverId":""}""")
        assertEquals("", settings.driverId)
    }

    private fun validate(
        entries: List<DriverFileInfo> = validEntries,
        metadata: Map<String, String> = validMetadata,
    ) = DriverArchivePolicy.validate("A".repeat(64), entries, metadata)

    private fun assertRejected(
        entries: List<DriverFileInfo>,
        metadata: Map<String, String>,
        expected: DriverArchiveError,
    ) {
        val rejected = assertIs<DriverImportResult.Rejected>(validate(entries, metadata))
        assertEquals(expected, rejected.error)
    }
}
