package com.yiku.ptzcontrol.service

import android.util.Log
import com.yiku.ptzcontrol.utils.CommonMethods.bytesToHex
import com.yiku.ptzcontrol.utils.MsgCallback
import com.yiku.ptzcontrol.utils.ZT6Msg
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayList
import kotlin.concurrent.thread

class ZT6Service: BaseService() {
    private val TAG = "ZT6Service"
    var msgCallbacks: List<MsgCallback> = ArrayList()
    private val port = 37260
    private lateinit var client: Socket
    private var out: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isConnected = false
    override val colorList = listOf("白热", "辉金", "铁红", "彩虹", "微光", "极光", "红热", "丛林", "医疗", "黑热", "金红")

    override fun connect(host: String): Boolean {
        return try {
            Log.i(TAG, "host:${host}, port:${port}")
            client = Socket(host, port)
            out = client.getOutputStream()
            Log.i(TAG, "连接成功")
            isConnected = true
            // 连接成功后，直接将默认视频拼接模式设置成：非拼接模式 (主码流：变焦 副码流：热成像)
            setVideoStreamMode(3)
            Thread.sleep(100)
            getPseudoColor() // 获取当前伪彩设置
            Thread.sleep(100)
            getConfig() // 获取云台配置信息
            inputStream = client.getInputStream()
            thread {
                Log.i(TAG, "recv start...")
                try{
                    while (client.isConnected) {
                        val recv = ByteArray(1024)
                        inputStream?.read(recv)
                        if (recv.isEmpty()) {
                            continue
                        }
                        Log.i(TAG, "recv:${bytesToHex(recv)}")
                        for (msgCallback in msgCallbacks) {
                            msgCallback.onMsg(recv)
                        }
                    }
                }
                catch (e: Exception) {
                    Log.e(TAG, e.toString());
                }
            }
            true
        } catch (e: Exception) {
            isConnected = false
            Log.e(TAG, "连接失败:${e}")
            false
        }
    }

    override fun getIsConnected(): Boolean {
        return isConnected
    }

    override fun disconnect() {
        if(getIsConnected()) {
            isConnected = false
            client.close()
            Log.i(TAG, "连接已关闭")
        }
    }

    override fun registMsgCallback(msgCallback: MsgCallback) {
        this.msgCallbacks += msgCallback
    }

    override fun send(data: ByteArray) {
        thread {
            try {
                Log.i(TAG, "ZT6双光吊舱，sendData:${bytesToHex(data)}")
                //向输出流中写入数据，传向服务端
                if (!getIsConnected()) {
                    return@thread
                }
                out?.write(data)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "ZT6双光吊舱消息发送异常：$e")
                disconnect()
            }
        }
    }

    override fun heartbeat() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x00
        msg.DATA = ByteArray(1)
        send(msg.getMsg())
    }

    // 设置视频流拼接模式
    /*
    * 0：拼接模式 (主码流：变焦&热成像 副码流：广角)
    * 1：拼接模式 (主码流：广角&热成像 副码流：变焦)
    * 2：拼接模式 (主码流：变焦&广角 副码流：热成像)
    * 3：非拼接模式 (主码流：变焦 副码流：热成像)
    * 4：非拼接模式 (主码流：变焦 副码流：广角)
    * 5：非拼接模式 (主码流：广角 副码流：热成像)
    * 6：非拼接模式 (主码流：广角 副码流：变焦)
    * 7：非拼接模式 (主码流：热成像 副码流：变焦)
    * 8：非拼接模式 (主码流：热成像 副码流：广角)
    * */
    fun setVideoStreamMode(mode: Int) {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x11
        msg.DATA = ByteArray(1)
        msg.DATA[0] = mode.toByte()
        send(msg.getMsg())
    }

    override fun turnLeft() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x07
        msg.DATA = ByteArray(2)
        msg.DATA[0] = -50
        msg.DATA[1] = 0
        send(msg.getMsg())
    }

    override fun turnRight() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x07
        msg.DATA = ByteArray(2)
        msg.DATA[0] = 50
        msg.DATA[1] = 0
        send(msg.getMsg())
    }

    override fun turnUpwards() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x07
        msg.DATA = ByteArray(2)
        msg.DATA[0] = 0
        msg.DATA[1] = 50
        send(msg.getMsg())
    }

    override fun turnDownwards() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x07
        msg.DATA = ByteArray(2)
        msg.DATA[0] = 0
        msg.DATA[1] = -50
        send(msg.getMsg())
    }

    override fun yawAndPitchToCenter() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x08
        msg.DATA = ByteArray(1)
        msg.DATA[0] = 1
        send(msg.getMsg())
    }

    override fun stopMove() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x07
        msg.DATA = ByteArray(2)
        msg.DATA[0] = 0
        msg.DATA[1] = 0
        send(msg.getMsg())
    }

    override fun getPseudoColor() {
        val msg = ZT6Msg()
        msg.CTRL = 0x01 // 0x01：需要返回信息
        msg.CMD_ID = 0x1A
        msg.DATA = ByteArray(1)
        send(msg.getMsg())
    }

    override fun setPseudoColor(colorName: String) {
        var colorCode = 0
        // 根据选择的滤镜执行不同操作
        when (colorName) {
            "白热" -> {
                colorCode = 0
            }
            "辉金" -> {
                colorCode = 2
            }
            "铁红" -> {
                colorCode = 3
            }
            "彩虹" -> {
                colorCode = 4
            }
            "微光" -> {
                colorCode = 5
            }
            "极光" -> {
                colorCode = 6
            }
            "红热" -> {
                colorCode = 7
            }
            "丛林" -> {
                colorCode = 8
            }
            "医疗" -> {
                colorCode = 9
            }
            "黑热" -> {
                colorCode = 10
            }
            "金红" -> {
                colorCode = 11
            }
        }
        val msg = ZT6Msg()
        msg.CMD_ID = 0x1B
        msg.DATA = ByteArray(1)
        msg.DATA[0] = colorCode.toByte()
        send(msg.getMsg())
    }

    override fun convertPseudoColorInt2Str(colorCode: Int): String {
        return when (colorCode) {
            0 -> "白热"
            2 -> "辉金"
            3 -> "铁红"
            4 -> "彩虹"
            5 -> "微光"
            6 -> "极光"
            7 -> "红热"
            8 -> "丛林"
            9 -> "医疗"
            10 -> "黑热"
            11 -> "金红"
            else -> "白热"
        }
    }

    // 缩放
    override fun zoom(type: String) {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x05
        msg.DATA = ByteArray(1)
        msg.DATA[0] = when(type) {
            "enlarge" -> 1.toByte()
            "reduce" -> (-1).toByte()
            "stop" -> 0.toByte()
            else -> 0.toByte()
        }
        send(msg.getMsg())
    }

    // 拍照
    override fun photograph() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x0C
        msg.DATA = ByteArray(1)
        msg.DATA[0] = 0
        send(msg.getMsg())
    }

    // 录像
    override fun video() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x0C
        msg.DATA = ByteArray(1)
        msg.DATA[0] = 2
        send(msg.getMsg())
    }

    // 获取云台配置信息
    override fun getConfig() {
        val msg = ZT6Msg()
        msg.CMD_ID = 0x0A
        msg.DATA = ByteArray(0)
        send(msg.getMsg())
    }
}