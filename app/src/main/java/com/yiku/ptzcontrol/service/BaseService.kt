package com.yiku.ptzcontrol.service

open class BaseService {
    open fun connect(host: String): Boolean { return false }
    open fun disconnect() {}
    open fun getIsConnected(): Boolean { return false }
    open fun send(data: String) {}
    open fun turnLeft() {}
    open fun turnRight() {}
    open fun turnUpwards() {}
    open fun turnDownwards() {}
    open fun yawToCenter() {}
    open fun yawAndPitchToCenter() {}
    open fun pitchDownwards() {}
    open fun setIp(ip: String) {}
}