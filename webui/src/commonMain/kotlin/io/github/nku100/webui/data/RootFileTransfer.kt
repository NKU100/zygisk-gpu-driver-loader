package io.github.nku100.webui.data

import kotlin.io.encoding.Base64

internal object RootFileTransfer {
    const val CHUNK_SIZE = 12 * 1024

    suspend fun transfer(
        read: suspend (ByteArray) -> Int,
        appendBase64: suspend (String) -> Unit,
    ): String {
        val buffer = ByteArray(CHUNK_SIZE)
        val pending = ByteArray(CHUNK_SIZE)
        var pendingSize = 0
        val hash = Sha256()
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) throw DriverStoreException(DriverArchiveError.TRANSFER_FAILED)
            require(count <= CHUNK_SIZE)
            hash.update(buffer, count)
            var offset = 0
            while (offset < count) {
                val copied = minOf(CHUNK_SIZE - pendingSize, count - offset)
                buffer.copyInto(pending, pendingSize, offset, offset + copied)
                pendingSize += copied
                offset += copied
                if (pendingSize == CHUNK_SIZE) {
                    appendBase64(Base64.encode(pending))
                    pendingSize = 0
                }
            }
        }
        if (pendingSize > 0) appendBase64(Base64.encode(pending.copyOf(pendingSize)))
        return hash.hexDigest()
    }
}
