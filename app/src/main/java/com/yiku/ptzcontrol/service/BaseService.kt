package com.yiku.ptzcontrol.service

import com.yiku.ptzcontrol.utils.MsgCallback

open class BaseService {
    interface OnDataReceivedListener {
        fun onDataReceived(data: String)
        fun onError(error: String)
    }
    open val colorList = listOf("")
    open fun setGlobalListener(listener: OnDataReceivedListener?) {}
    open fun connect(host: String): Boolean { return false } // 连接
    open fun disconnect() {} // 断连
    open fun getIsConnected(): Boolean { return false } // 查看连接状态
    open fun registMsgCallback(msgCallback: MsgCallback) {} // TCP连接时用，注册回调函数
    open fun send(data: String) {} // 发送消息
    open fun send(data: ByteArray) {}
    open fun heartbeat() {} // 发送心跳包
    open fun turnLeft() {} // 云台向左转
    open fun turnRight() {} // 云台向右转
    open fun turnUpwards() {} // 云台向上转
    open fun turnDownwards() {} // 云台向下转
    open fun yawToCenter() {} // 云台偏航回中
    open fun yawAndPitchToCenter() {} // 云台偏航和俯仰回中
    open fun pitchDownwards() {} // 云台俯仰朝下
    open fun stopMove() {} // 云台停止转动 ZT6
    open fun ptzAnglePush(switch: Boolean) {} // 云台角度信息推送开关，true可以定时获取到云台角度信息，false停止推送
    open fun setIp(ip: String) {} // 设置相机ip(暂不可用)
    open fun getPseudoColor(listener: OnDataReceivedListener) {} // 获取相机伪彩
    open fun getPseudoColor() {} // 获取相机伪彩
    open fun setPseudoColor(colorName: String) {} // 设置相机伪彩
    open fun convertPseudoColorInt2Str(colorCode: Int): String { return "" } // 相机伪彩数字转为文字
    open fun enlarge() {} // 相机变焦——放大
    open fun reduce() {} // 相机变焦——缩小
    open fun zoom(type: String) {} // 缩放：enlarge：放大，reduce：缩小，stop：停止缩放
    open fun photograph() {} // 拍照
    open fun video() {} // 录像
    open fun getConfig() {} // 获取云台配置信息
}