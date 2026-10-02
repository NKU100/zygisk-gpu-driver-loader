package io.github.nku100.webui.data

internal fun arm64ElfHeader(): ByteArray = ByteArray(64).apply {
    this[0] = 0x7f
    this[1] = 'E'.code.toByte()
    this[2] = 'L'.code.toByte()
    this[3] = 'F'.code.toByte()
    this[4] = 2
    this[5] = 1
    this[6] = 1
    this[16] = 3
    this[18] = 0xb7.toByte()
    this[20] = 1
    this[52] = 64
}
