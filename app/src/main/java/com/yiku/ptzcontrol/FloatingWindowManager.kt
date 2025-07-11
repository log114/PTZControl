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
import android.widget.LinearLayout
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.StyledPlayerView

class FloatingWindowManager(private val context: Context) {

    private val TAG = "FloatingWindowDebug"
    private var floatingView: View? = null
    private var player1: ExoPlayer? = null
    private var player2: ExoPlayer? = null

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
    fun showFloatingWindow(
        player1: ExoPlayer,
        player2: ExoPlayer,
        playWhenReady1: Boolean,
        playWhenReady2: Boolean,
        position1: Long,
        position2: Long
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

        this.player1 = player1
        this.player2 = player2

        // 确保播放器处于可用状态
        if (player1.playbackState == ExoPlayer.STATE_IDLE) {
            player1.prepare()
        }
        if (player2.playbackState == ExoPlayer.STATE_IDLE) {
            player2.prepare()
        }

        try {
            // 创建悬浮窗视图
            floatingView = LayoutInflater.from(context).inflate(R.layout.floating_layout, null).apply {
                // 视频容器1
                findViewById<StyledPlayerView>(R.id.floatingPlayer1).apply {
                    player = player1
                    useController = false
                }

                // 视频容器2
                findViewById<StyledPlayerView>(R.id.floatingPlayer2).apply {
                    player = player2
                    useController = false
                }

                // 恢复播放状态
                player1.seekTo(position1)
                player2.seekTo(position2)
                player1.playWhenReady = playWhenReady1
                player2.playWhenReady = playWhenReady2

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

        // 将播放器控制权交还给Activity
        player1?.let {
            (context as? MainActivity)?.findViewById<StyledPlayerView>(R.id.playerView1)?.player = it
        }
        player2?.let {
            (context as? MainActivity)?.findViewById<StyledPlayerView>(R.id.playerView2)?.player = it
        }

        // 清除引用
        player1 = null
        player2 = null
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