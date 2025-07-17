package com.yiku.ptzcontrol.service

import android.util.Log
import com.yiku.ptzcontrol.utils.YunZhuoCrc
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class C12Service: BaseService() {
    private lateinit var socket: DatagramSocket
    private lateinit var serverAddress: InetAddress
    private var currentHost = ""
    private val serverPort = 5000
    private var isConnected = false
    private val TAG = "C12Service"
    override val colorList = listOf("白热", "辉金", "铁红", "彩虹", "微光", "极光", "红热", "丛林", "医疗", "黑热", "金红")

    override fun connect(host: String): Boolean {
        currentHost = host
        return try {
            // 关闭现有连接
            disconnect()

            // 创建新连接
            socket = DatagramSocket().apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 3000 // 3秒超时
            }
            serverAddress = try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                Log.w(TAG, "使用广播地址作为备用方案")
                InetAddress.getByName("255.255.255.255")
            }
            isConnected = true
            Log.i(TAG, "已连接到 $host:$serverPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}")
            false
        }
    }

    override fun getIsConnected(): Boolean {
        return isConnected
    }

    override fun disconnect() {
        if (::socket.isInitialized) {
            socket.close()
            isConnected = false
            Log.i(TAG, "连接已关闭")
        }
    }

    override fun send(data: String, listener: OnDataReceivedListener?) {
        if (!isConnected) {
            Log.w(TAG, "尝试发送但未连接")
            if (!connect(currentHost)) {
                Log.e(TAG, "重新连接失败，无法发送命令")
                return
            }
        }
        Thread {
            try {
                val command = YunZhuoCrc.formatCommandWithCrc(data)
                Log.i(TAG, "发送：$command")
                Log.d(TAG, "命令字节: ${command.toByteArray().joinToString { "%02x".format(it) }}")
                Log.i(TAG, "ip：$serverAddress, 端口：$serverPort")
                val commandBytes = command.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(commandBytes, commandBytes.size, serverAddress, serverPort)
                socket.send(packet)
                Log.i(TAG, "UDP数据包已发送")
                listener?.let {
                    // 设置超时时间，避免一直阻塞
                    socket.soTimeout = 3000
                    try {
                        // 接收缓冲区
                        val buffer = ByteArray(1024)
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                        Log.i(TAG, "收到响应: $response")

                        it.onDataReceived(response)
                    } catch (e: SocketTimeoutException) {
                        Log.e(TAG, "接收超时")
                        it.onError("接收超时")
                    } catch (e: Exception) {
                        Log.e(TAG, "接收异常: ${e.message}")
                        it.onError("接收异常: ${e.message}")
                    } finally {
                        // 重置超时时间
                        socket.soTimeout = 3000 // 或者你原来的超时设置
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送失败: ${e.message}")
            }
        }.start()
    }

    override fun turnLeft() {
        send("#TPUG2wGSYE2")
    }

    override fun turnRight() {
        send("#TPUG2wGSY1E")
    }

    override fun turnUpwards() {
        send("#TPUG2wGSP1E")
    }

    override fun turnDownwards() {
        send("#TPUG2wGSPE2")
    }

    override fun yawToCenter() {
        send("#TPUG6wGAY00002D")
    }

    override fun yawAndPitchToCenter() {
        send("#TPUGCwGAM00002D00002D")
    }

    override fun pitchDownwards() {
        send("#TPUG6wGAPDCD82D")
    }

    override fun setIp(ip: String) {
        send("#tpUDDwIPV${ip}")
    }

    override fun getPseudoColor(listener: OnDataReceivedListener) {
        send("#TPUD2rIMG00", object : OnDataReceivedListener {
            override fun onDataReceived(data: String) {
                if(-1 != data.indexOf("#TPDU1rIMG")) {
                    parsePseudoColorResponse(data)?.let { listener.onDataReceived(it) }
                }
            }

            override fun onError(error: String) {
                listener.onError(error)
            }
        })
    }

    override fun setPseudoColor(colorName: String) {
        var colorCode = "01"
        // 根据选择的滤镜执行不同操作
        when (colorName) {
            "白热" -> {
                colorCode = "01"
            }
            "辉金" -> {
                colorCode = "03"
            }
            "铁红" -> {
                colorCode = "04"
            }
            "彩虹" -> {
                colorCode = "05"
            }
            "微光" -> {
                colorCode = "06"
            }
            "极光" -> {
                colorCode = "07"
            }
            "红热" -> {
                colorCode = "08"
            }
            "丛林" -> {
                colorCode = "09"
            }
            "医疗" -> {
                colorCode = "0A"
            }
            "黑热" -> {
                colorCode = "0B"
            }
            "金红" -> {
                colorCode = "0C"
            }
        }
        send("#TPUD2wIMG${colorCode}")
    }

    // 解析伪彩响应
    private fun parsePseudoColorResponse(response: String): String? {
        // 假设响应格式为 "#TPUD2rIMG01" 表示白热，我们需要解析出01并映射为颜色名称
        // 注意：响应可能包含CRC校验，我们需要先校验再解析，这里为了简单假设格式固定
        if (response.length < 12) {
            return null
        }
        // 提取颜色代码，假设颜色代码在响应字符串的第10-11位（从0开始计数）
        val colorCode = response.substring(10, 12)
        return when (colorCode) {
            "01" -> "白热"
            "03" -> "辉金"
            "04" -> "铁红"
            "05" -> "彩虹"
            "06" -> "微光"
            "07" -> "极光"
            "08" -> "红热"
            "09" -> "丛林"
            "0A" -> "医疗"
            "0B" -> "黑热"
            "0C" -> "金红"
            else -> null
        }
    }
}