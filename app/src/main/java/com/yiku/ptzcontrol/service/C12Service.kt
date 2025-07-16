package com.yiku.ptzcontrol.service

import android.util.Log
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import com.yiku.ptzcontrol.utils.YunZhuoCrc
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException

class C12Service: BaseService() {
    private lateinit var socket: DatagramSocket
    private lateinit var serverAddress: InetAddress
    private var currentHost = ""
    private val serverPort = 5000
    private var isConnected = false
    private val TAG = "C12Service"

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

    override fun send(data: String) {
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
}