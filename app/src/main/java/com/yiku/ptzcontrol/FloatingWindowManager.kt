package com.yiku.ptzcontrol

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yiku.ptzcontrol.utils.RtspPlayer

@UnstableApi
class FloatingWindowManager(private val context: Context) {

    private val TAG = "FloatingWindowDebug"
    private var floatingView: View? = null
    private var floatingPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    // 新增视图引用
    private var playerContainer: FrameLayout? = null
    private var videoContainer: LinearLayout? = null

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
        streamUrl: String?,
        playWhenReady: Boolean
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

        try {
            // 创建悬浮窗视图
            floatingView = LayoutInflater.from(context).inflate(R.layout.floating_layout, null).apply {
                // 保存视图引用
                playerContainer = findViewById(R.id.playerContainer)
                playerView = findViewById(R.id.floatingPlayer)

                // 创建播放器（如果有流）
                if (streamUrl != null) {
                    floatingPlayer = RtspPlayer.createIndependentPlayer(
                        context,
                        url = streamUrl,
                        startPosition = 0,
                        playWhenReady = playWhenReady
                    ).also { player ->
                        playerView?.player = player
                    }
                }

                // 回到APP
                findViewById<ImageButton>(R.id.exitBtn).setOnClickListener {
                    openMainApp()
                }

                // 关闭整个悬浮窗按钮
                findViewById<ImageButton>(R.id.closeBtn).setOnClickListener {
                    closeFloatingWindow()
                }

                // 整个悬浮窗可拖动
                setOnTouchListener(FloatingTouchListener(windowManager))
            }

            // 设置窗口参数 - 改为固定宽度填充屏幕
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, // 自适应宽度
                WindowManager.LayoutParams.WRAP_CONTENT, // 高度自适应
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = context.resources.displayMetrics.widthPixels - 400 // 从右侧偏移
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
        floatingView?.let {
            try {
                windowManager.removeView(it)
                Log.d(TAG, "Floating window removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating window: ${e.message}")
            }

            // 清除所有视图引用
            playerContainer = null
            videoContainer = null
            playerView = null
            floatingView = null
        }

        floatingPlayer?.release()
        floatingPlayer = null
    }

    // 打开应用主界面
    private fun openMainApp() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // 现代方法：尝试使用AppTask（API 21+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val appTasks = am.appTasks
                if (appTasks.isNotEmpty()) {
                    appTasks[0].moveToFront()
                    Log.d(TAG, "使用AppTask激活已有实例")
                    return
                }
            }

            // 兼容所有版本的回退方法
            val taskInfoList = am.getRunningTasks(10)
            val isAppRunning = taskInfoList.any { it.topActivity?.packageName == context.packageName }

            if (isAppRunning) {
                Log.d(TAG, "激活已有应用实例")
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    context.startActivity(this)
                }
            } else {
                Log.d(TAG, "应用未运行，重新启动")
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(this)
                } ?: run {
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(this)
                    }
                    // 重新打开的应用无法关闭当前悬浮窗，主动关闭
                    closeFloatingWindow()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "启动应用权限不足: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "打开应用错误: ${e.message}")
            // 最终回退方案
            try {
                val fallbackIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "最终回退方案失败: ${ex.message}")
            }
        }
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