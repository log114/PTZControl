package com.yiku.ptzcontrol.utils

interface MsgCallback {
    fun getId(): String
    fun onMsg(msg: ByteArray)
}