package com.yiku.ptzcontrol.service

open class BaseService {
    interface OnDataReceivedListener {
        fun onDataReceived(data: String)
        fun onError(error: String)
    }
    open val colorList = listOf("")
    open fun connect(host: String): Boolean { return false } // 连接
    open fun disconnect() {} // 断连
    open fun getIsConnected(): Boolean { return false } // 查看连接状态
    open fun send(data: String, listener: OnDataReceivedListener? = null) {} // 发送消息
    open fun turnLeft() {} // 云台向左转
    open fun turnRight() {} // 云台向右转
    open fun turnUpwards() {} // 云台向上转
    open fun turnDownwards() {} // 云台向下转
    open fun yawToCenter() {} // 云台偏航回中
    open fun yawAndPitchToCenter() {} // 云台偏航和俯仰回中
    open fun pitchDownwards() {} // 云台俯仰朝下
    open fun setIp(ip: String) {} // 设置相机ip(暂不可用)
    open fun getPseudoColor(listener: OnDataReceivedListener) {} // 获取相机伪彩
    open fun setPseudoColor(colorName: String) {} // 设置相机伪彩
    open fun enlarge() {} // 相机变焦——放大
    open fun reduce() {} // 相机变焦——缩小
}