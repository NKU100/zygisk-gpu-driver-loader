package io.github.nku100.webui.data

/** Metadata for an imported GPU driver. */
data class DriverInfo(
    val driverId: String,
    val name: String,
    val libraryName: String,
    val abi: String,
)

/** ZIP entry information needed to validate an archive without extracting it. */
data class DriverFileInfo(
    val path: String,
    val isRegularFile: Boolean,
    val isSymbolicLink: Boolean = false,
)

/** Result of validating a driver archive. */
sealed interface DriverImportResult {
    data class Accepted(val driver: DriverInfo) : DriverImportResult
    data class Rejected(val error: DriverArchiveError) : DriverImportResult
}

enum class DriverArchiveError {
    INVALID_ARCHIVE_HASH,
    INVALID_ENTRY_PATH,
    SYMBOLIC_LINK,
    MISSING_META_JSON,
    INVALID_META_JSON_ENTRY,
    MISSING_LIBRARY_NAME,
    DUPLICATE_LIBRARY_NAME,
    LIBRARY_OUTSIDE_ROOT,
    LIBRARY_NOT_FOUND,
    LIBRARY_NAME_MISMATCH,
    UNSUPPORTED_ABI,
}
