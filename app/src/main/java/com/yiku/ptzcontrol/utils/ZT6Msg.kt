package com.yiku.ptzcontrol.utils

data class ZT6Msg(
    private val STX: ByteArray = byteArrayOf(0x55.toByte(), 0x66.toByte()),
    var CTRL: Byte = 0x00.toByte(), // 0x00: 不需要返回响应信息，0x01：需要返回响应信息
    private var Data_len: ByteArray = byteArrayOf(0x00.toByte(), 0x00.toByte()),
    private val SEQ: ByteArray = byteArrayOf(0x00.toByte(), 0x00.toByte()),
    var CMD_ID: Byte = 0x00.toByte(),
    var DATA: ByteArray = ByteArray(0),
    private var CRC16: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
) {
    fun getMsg(): ByteArray {
        // 更新数据长度
        Data_len = intToLittleEndianByteArray(DATA.size)

        // 构建CRC计算数据（不含STX）
        val checksumData = ByteArray(2 + 1 + 2 + 2 + 1 + DATA.size) { index ->
            when (index) {
                in 0..1  -> STX[index]
                2 -> CTRL
                in 3..4 -> Data_len[index - 3]
                in 5..6 -> SEQ[index - 5]
                7 -> CMD_ID
                else -> DATA[index - 8]
            }
        }

        // 计算CRC（使用修复后的实现）
        val crcValue = Crc16.calculate(checksumData)
        CRC16 = intToLittleEndianByteArray(crcValue.toInt())

        // 构建完整消息
        return checksumData + CRC16
    }

    // 小端序转换
    fun intToLittleEndianByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte()
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ZT6Msg

        if (Data_len != other.Data_len) return false
        if (CMD_ID != other.CMD_ID) return false
        if (CRC16 != other.CRC16) return false
        if (!STX.contentEquals(other.STX)) return false
        if (!DATA.contentEquals(other.DATA)) return false

        return true
    }

    override fun hashCode(): Int {
        var result: Int = CTRL.toInt()
        result = 31 * result + CMD_ID
        result = 31 * result + STX.contentHashCode()
        result = 31 * result + Data_len.contentHashCode()
        result = 31 * result + SEQ.contentHashCode()
        result = 31 * result + DATA.contentHashCode()
        result = 31 * result + CRC16.contentHashCode()
        return result
    }
}