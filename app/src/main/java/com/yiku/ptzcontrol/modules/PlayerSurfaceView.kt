package com.yiku.ptzcontrol.modules

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class PlayerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    private var isReady = false

    init {
        holder.addCallback(this) // 设置Surface生命周期回调
        isFocusable = false // 设置不可获取焦点，避免影响外部的触摸事件
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 当Surface尺寸变化时（如屏幕旋转），可在此处处理
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isReady = false
    }
}