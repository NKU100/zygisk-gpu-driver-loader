package io.github.nku100.webui.data

internal object DriverElfHeader {
    const val SIZE = 64

    fun isArm64Library(header: ByteArray): Boolean {
        if (header.size < SIZE) return false
        fun byte(offset: Int) = header[offset].toInt() and 0xff
        fun short(offset: Int) = byte(offset) or (byte(offset + 1) shl 8)
        return byte(0) == 0x7f && byte(1) == 0x45 && byte(2) == 0x4c && byte(3) == 0x46 &&
            byte(4) == 2 && byte(5) == 1 && byte(6) == 1 &&
            short(16) == 3 && short(18) == 183 &&
            byte(20) == 1 && byte(21) == 0 && byte(22) == 0 && byte(23) == 0 && short(52) == SIZE
    }
}
