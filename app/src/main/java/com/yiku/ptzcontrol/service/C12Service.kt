package com.yiku.ptzcontrol.service

import android.util.Log
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import com.yiku.ptzcontrol.utils.YunZhuoCrc
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class C12Service: BaseService() {
    private lateinit var socket: DatagramSocket
    private lateinit var serverAddress: InetAddress
    private val serverPort = 5000
    private val TAG = "C12Service"

    override fun connect(host: String): Boolean {
        socket = DatagramSocket()
        serverAddress = InetAddress.getByName(host)
        return true
    }

    override fun getIsConnected(): Boolean {
        return ::socket.isInitialized
    }

    override fun disconnect() {
        socket.close()
    }

    override fun send(data: String) {
        val command = YunZhuoCrc.formatCommandWithCrc(data)
        Log.i(TAG, "发送：$command")
        val commandBytes = command.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(commandBytes, commandBytes.size, serverAddress, serverPort)
        socket.send(packet)
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
        send("#TPUG6wGAM00002D00002D")
    }

    override fun pitchDownwards() {
        send("#TPUG6wGAPDCD82D")
    }

    override fun setIp(ip: String) {
        send("#tpUDDwIPV${ip}")
    }
}