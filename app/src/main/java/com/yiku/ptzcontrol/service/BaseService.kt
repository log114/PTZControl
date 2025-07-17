package com.yiku.ptzcontrol.service

open class BaseService {
    interface OnDataReceivedListener {
        fun onDataReceived(data: String)
        fun onError(error: String)
    }
    open val colorList = listOf("")
    open fun connect(host: String): Boolean { return false }
    open fun disconnect() {}
    open fun getIsConnected(): Boolean { return false }
    open fun send(data: String, listener: OnDataReceivedListener? = null) {}
    open fun turnLeft() {}
    open fun turnRight() {}
    open fun turnUpwards() {}
    open fun turnDownwards() {}
    open fun yawToCenter() {}
    open fun yawAndPitchToCenter() {}
    open fun pitchDownwards() {}
    open fun setIp(ip: String) {}
    open fun getPseudoColor(listener: OnDataReceivedListener) {}
    open fun setPseudoColor(colorName: String) {}
}