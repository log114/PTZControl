package com.yiku.ptzcontrol.utils

object Crc16 {
    private val xmodemTable: IntArray by lazy {
        IntArray(256) { i ->
            var crc = i shl 8  // 字节置于高8位
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
            crc and 0xFFFF  // 限制为16位
        }
    }

    fun calculate(data: ByteArray): Int {
        var crc = 0x0000
        for (byte in data) {
            val idx = (crc shr 8) xor (byte.toInt() and 0xFF)  // 高8位与字节异或
            crc = ((crc shl 8) and 0xFFFF) xor xmodemTable[idx]
        }
        return crc
    }

    /**
     * 验证 CRC 是否正确（Kotlin 扩展函数）
     * @param data 包含原始数据和末尾2字节CRC的字节数组
     * @return 验证结果（true=校验通过）
     */
    fun ByteArray.verifyCRC16(): Boolean {
        if (size < 2) return false
        val dataWithoutCRC = copyOfRange(0, size - 2)
        val expectedCRC = calculate(dataWithoutCRC)
        val actualCRC = ((this[size - 2].toInt() and 0xFF) shl 8) or (this[size - 1].toInt() and 0xFF)
        return expectedCRC.toInt() == actualCRC
    }
}