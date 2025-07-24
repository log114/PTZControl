package com.yiku.ptzcontrol.utils

import java.math.BigInteger

object CommonMethods {
    fun hexToSignedInt(hex: String): Int {
        // 1. 验证输入（非空且有效的十六进制字符串）
        require(hex.isNotEmpty()) { "Input cannot be empty" }
        require(hex.matches(Regex("[0-9a-fA-F]+"))) { "Invalid hex string" }

        // 2. 将十六进制字符串转换为 BigInteger
        val bigInt = BigInteger(hex, 16)

        // 3. 计算位数（每个十六进制字符代表4位）
        val bitLength = hex.length * 4

        // 4. 检查是否为负数（最高位为1）
        return if (bitLength > 0 && bigInt.testBit(bitLength - 1)) {
            // 负数处理：减去 2^bitLength
            bigInt.subtract(BigInteger.ONE.shiftLeft(bitLength)).toInt()
        } else {
            // 正数直接转换
            bigInt.toInt()
        }
    }
}