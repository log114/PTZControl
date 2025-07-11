package com.yiku.ptzcontrol.utils

object YunZhuoCrc {
    private val CRC_MASK = 0xFF

    // 主计算方法
    fun formatCommandWithCrc(baseCommand: String): String {
        val crc = calculateSimpleCrc(baseCommand)
        return buildString {
            append(baseCommand)
            append(crc.toHex())
        }
    }

    // CRC 计算（按字节求和）
    private fun calculateSimpleCrc(data: String): Int {
        return data.sumOf { it.code } and CRC_MASK
    }

    // 字节转两位十六进制
    private fun Int.toHex(): String {
        return this.toString(16).run {
            uppercase().padStart(2, '0')
        }
    }
}