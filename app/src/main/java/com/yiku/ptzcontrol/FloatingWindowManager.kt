package com.yiku.ptzcontrol

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yiku.ptzcontrol.utils.RtspPlayer

class FloatingWindowManager(private val context: Context) {

    private val TAG = "FloatingWindowDebug"
    private var floatingView: View? = null
    private var floatingPlayer1: ExoPlayer? = null
    private var floatingPlayer2: ExoPlayer? = null

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // 检查悬浮窗权限
    fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context).also {
                Log.d(TAG, "Check overlay permission: $it")
            }
        } else {
            true
        }
    }

    // 显示悬浮窗（添加状态恢复参数）
    @OptIn(UnstableApi::class)
    fun showFloatingWindow(
        streamUrl1: String,
        streamUrl2: String,
        playWhenReady1: Boolean,
        playWhenReady2: Boolean
    ) {
        Log.d(TAG, "showFloatingWindow called")

        if (!checkOverlayPermission()) {
            Log.e(TAG, "Cannot show floating window: permission denied")
            return
        }

        if (floatingView != null) {
            Log.d(TAG, "Floating window already exists, closing first")
            closeFloatingWindow()
        }

        floatingPlayer1 = RtspPlayer.createIndependentPlayer(
            context,
            url = streamUrl1,
            startPosition = 0, // 从0开始避免积累延迟
            playWhenReady = playWhenReady1
        )

        floatingPlayer2 = RtspPlayer.createIndependentPlayer(
            context,
            url = streamUrl2,
            startPosition = 0, // 从0开始避免积累延迟
            playWhenReady = playWhenReady2
        )

        try {
            // 创建悬浮窗视图
            floatingView = LayoutInflater.from(context).inflate(R.layout.floating_layout, null).apply {
                // 视频容器1
                findViewById<PlayerView>(R.id.floatingPlayer1).apply {
                    player = floatingPlayer1
                    useController = false
                }

                // 视频容器2
                findViewById<PlayerView>(R.id.floatingPlayer2).apply {
                    player = floatingPlayer2
                    useController = false
                }

                // 关闭按钮点击事件
                findViewById<ImageButton>(R.id.closeBtn).setOnClickListener {
                    closeFloatingWindow()
                }

                // 整个悬浮窗可拖动
                setOnTouchListener(FloatingTouchListener(windowManager))
            }

            // 设置窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                x = 0
                y = 100
            }

            // 添加视图到窗口
            windowManager.addView(floatingView, params)
            Log.d(TAG, "Floating window added successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error creating floating window: ${e.message}")
            e.printStackTrace()
        }
    }

    fun isFloatingWindowShowing(): Boolean {
        return floatingView != null
    }

    // 关闭悬浮窗
    fun closeFloatingWindow() {
        Log.d(TAG, "准备关闭悬浮窗")
        Log.d(TAG, "$floatingView")
        floatingView?.let {
            try {
                windowManager.removeView(it)
                Log.d(TAG, "Floating window removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating window: ${e.message}")
            }
            floatingView = null
        }

        floatingPlayer1?.release()
        floatingPlayer2?.release()
        floatingPlayer1 = null
        floatingPlayer2 = null
    }
}

// 实现悬浮窗拖动功能
class FloatingTouchListener(private val windowManager: WindowManager) : View.OnTouchListener {
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
        val params = v.layoutParams as WindowManager.LayoutParams

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(v, params)
                return true
            }
        }
        return false
    }
}