package com.yiku.ptzcontrol.service

import android.util.Log
import com.yiku.ptzcontrol.utils.YunZhuoCrc
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

class C12Service: BaseService() {
    private lateinit var socket: DatagramSocket
    private lateinit var serverAddress: InetAddress
    private var currentHost = ""
    private val serverPort = 5000
    private var isConnected = false
    private val TAG = "C12Service"
    private var stopReceiver = false
    private val globalListener = AtomicReference<OnDataReceivedListener?>(null)
    private var serviceIsReady = false

    private var receiverThread: Thread? = null
    override val colorList = listOf("白热", "辉金", "铁红", "彩虹", "微光", "极光", "红热", "丛林", "医疗", "黑热", "金红")

    // 设置全局监听器（持续接收消息）
    override fun setGlobalListener(listener: OnDataReceivedListener?) {
        globalListener.set(listener)
    }

    // 启动持续接收线程
    private fun startReceiverThread() {
        receiverThread = Thread {
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            Log.i(TAG, "stopReceiver=$stopReceiver, isConnected=$isConnected")
            while (!stopReceiver && isConnected) {
                Log.i(TAG, "持续接收：stopReceiver=$stopReceiver, isConnected=$isConnected")
                try {
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    Log.i(TAG, "收到响应: $response")
                    serviceIsReady = true

                    // 触发全局监听器回调
                    globalListener.get()?.onDataReceived(response)
                } catch (e: SocketTimeoutException) {
                    // 超时继续循环
                    Log.e(TAG, "消息接收超时: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "持续接收异常: ${e.message}")
                    if (!stopReceiver) {
                        Thread.sleep(1000) // 暂停后重试
                    }
                }
            }
            Log.i(TAG, "接收线程停止")
        }.apply {
            start()
        }
    }

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
            // 启动持续接收线程
            stopReceiver = false
            startReceiverThread()
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
        stopReceiver = true
        receiverThread?.interrupt()
        receiverThread = null
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
                val commandBytes = command.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(commandBytes, commandBytes.size, serverAddress, serverPort)
                socket.send(packet)
                Log.i(TAG, "UDP数据包已发送")
            } catch (e: Exception) {
                Log.e(TAG, "发送失败: ${e.message}")
                globalListener.get()?.onError("发送失败: ${e.message}")
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

    override fun ptzAnglePush(switch: Boolean) {
        var data = "00"
        if(switch) {
            data = "01"
        }
        send("#TPUG2wGAA${data}")
    }

    override fun setIp(ip: String) {
        send("#tpUDDwIPV${ip}")
    }

    override fun getPseudoColor(listener: OnDataReceivedListener) {
        // 设置临时监听器（响应后自动移除）
        val tempListener = object : OnDataReceivedListener {
            override fun onDataReceived(data: String) {
                if(data.contains("#TPDU1rIMG")) {
                    parsePseudoColorResponse(data)?.let {
                        listener.onDataReceived(it)
                    }
                    globalListener.compareAndSet(this, null) // 移除临时监听器
                }
            }
            override fun onError(error: String) {
                Log.i(TAG, "热成像信息接收失败")
                listener.onError(error)
                globalListener.compareAndSet(this, null)
            }
        }

        // 注册临时监听器并发送命令
        globalListener.set(tempListener)
        Thread {
            while (!serviceIsReady) {
                Thread.sleep(3000)
                send("#TPUD2rIMG00")
            }
        }.apply {
            start()
        }
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

    override fun enlarge() {
        send("#TPUD2wDZM0A")
    }
    override fun reduce() {
        send("#TPUD2wDZM0B")
    }
}