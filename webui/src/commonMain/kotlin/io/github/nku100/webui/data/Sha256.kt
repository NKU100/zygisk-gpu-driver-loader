package io.github.nku100.webui.data

/** Incremental SHA-256 for archive and transfer verification on both platforms. */
internal class Sha256 {
    private val state = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
    private val block = ByteArray(64)
    private var used = 0
    private var length = 0L

    fun update(bytes: ByteArray, count: Int = bytes.size) {
        require(count in 0..bytes.size)
        length += count
        var offset = 0
        while (offset < count) {
            val take = minOf(64 - used, count - offset)
            bytes.copyInto(block, used, offset, offset + take)
            used += take
            offset += take
            if (used == 64) {
                compress()
                used = 0
            }
        }
    }

    fun hexDigest(): String {
        val bitLength = length * 8
        block[used++] = 0x80.toByte()
        if (used > 56) {
            while (used < 64) block[used++] = 0
            compress()
            used = 0
        }
        while (used < 56) block[used++] = 0
        for (shift in 56 downTo 0 step 8) block[used++] = (bitLength ushr shift).toByte()
        compress()
        return state.joinToString("") { word -> word.toUInt().toString(16).padStart(8, '0') }
    }

    private fun compress() {
        val words = IntArray(64)
        for (i in 0 until 16) {
            val j = i * 4
            words[i] = ((block[j].toInt() and 255) shl 24) or
                ((block[j + 1].toInt() and 255) shl 16) or
                ((block[j + 2].toInt() and 255) shl 8) or (block[j + 3].toInt() and 255)
        }
        for (i in 16 until 64) {
            val x = words[i - 15]
            val y = words[i - 2]
            words[i] = words[i - 16] +
                (x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)) +
                words[i - 7] + (y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10))
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in 0 until 64) {
            val choose = (e and f) xor (e.inv() and g)
            val majority = (a and b) xor (a and c) xor (b and c)
            val t1 = h + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + choose + K[i] + words[i]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + majority
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    companion object {
        fun digestHex(bytes: ByteArray): String = Sha256().apply { update(bytes) }.hexDigest()

        private val K = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}
