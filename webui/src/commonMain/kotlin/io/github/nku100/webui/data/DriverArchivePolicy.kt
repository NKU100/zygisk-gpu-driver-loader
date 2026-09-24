package io.github.nku100.webui.data

/** Pure validation policy for driver ZIP metadata. This class does not read or write files. */
object DriverArchivePolicy {
    const val SUPPORTED_ABI = "arm64-v8a"
    private const val META_FILE = "meta.json"

    fun validate(
        archiveSha256: String,
        entries: List<DriverFileInfo>,
        metaJson: Map<String, String>,
    ): DriverImportResult {
        if (!archiveSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            return DriverImportResult.Rejected(DriverArchiveError.INVALID_ARCHIVE_HASH)
        }
        val normalizedPaths = mutableListOf<Pair<DriverFileInfo, List<String>>>()
        for (entry in entries) {
            val path = normalizeRelativePath(entry.path)
                ?: return DriverImportResult.Rejected(DriverArchiveError.INVALID_ENTRY_PATH)
            if (entry.isSymbolicLink) {
                return DriverImportResult.Rejected(DriverArchiveError.SYMBOLIC_LINK)
            }
            normalizedPaths += entry to path
        }

        val metadataEntries = normalizedPaths.filter { (_, path) -> path.last() == META_FILE }
        if (metadataEntries.isEmpty()) {
            return DriverImportResult.Rejected(DriverArchiveError.MISSING_META_JSON)
        }
        val metadataEntry = metadataEntries.singleOrNull()
            ?: return DriverImportResult.Rejected(DriverArchiveError.INVALID_META_JSON_ENTRY)
        if (metadataEntry.second.size != 1 || !metadataEntry.first.isRegularFile) {
            return DriverImportResult.Rejected(DriverArchiveError.INVALID_META_JSON_ENTRY)
        }

        val libraryName = metaJson["libraryName"]?.takeIf { it.isNotBlank() }
            ?: return DriverImportResult.Rejected(DriverArchiveError.MISSING_LIBRARY_NAME)
        if (libraryName == META_FILE) {
            return DriverImportResult.Rejected(DriverArchiveError.LIBRARY_NAME_MISMATCH)
        }
        val abi = metaJson["abi"].orEmpty()
        if (abi != SUPPORTED_ABI) {
            return DriverImportResult.Rejected(DriverArchiveError.UNSUPPORTED_ABI)
        }

        val libraryEntries = normalizedPaths.filter { (_, path) -> path.last() == libraryName }
        if (libraryEntries.size > 1) {
            return DriverImportResult.Rejected(DriverArchiveError.DUPLICATE_LIBRARY_NAME)
        }
        val libraryEntry = libraryEntries.singleOrNull()
            ?: return DriverImportResult.Rejected(DriverArchiveError.LIBRARY_NOT_FOUND)
        if (libraryEntry.second.size != 1) {
            return DriverImportResult.Rejected(DriverArchiveError.LIBRARY_OUTSIDE_ROOT)
        }
        if (!libraryEntry.first.isRegularFile) {
            return DriverImportResult.Rejected(DriverArchiveError.LIBRARY_NOT_FOUND)
        }
        if (libraryEntry.first.path != libraryName) {
            return DriverImportResult.Rejected(DriverArchiveError.LIBRARY_NAME_MISMATCH)
        }

        val name = metaJson["name"]?.takeIf { it.isNotBlank() } ?: libraryName
        val driverId = stableDriverId(archiveSha256, libraryName, abi)
        return DriverImportResult.Accepted(DriverInfo(driverId, name, libraryName, abi))
    }

    private fun normalizeRelativePath(path: String): List<String>? {
        if (path.isEmpty() || path.startsWith('/') || path.startsWith('\\') || '\\' in path || '\u0000' in path) return null
        if (path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] == '/') return null
        val components = path.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) return null
        return components
    }

    private fun stableDriverId(archiveSha256: String, libraryName: String, abi: String): String =
        "${archiveSha256.lowercase()}:$abi:${libraryName.encodeToByteArray().joinToString("") { byte -> "%02x".format(byte) }}"
}
