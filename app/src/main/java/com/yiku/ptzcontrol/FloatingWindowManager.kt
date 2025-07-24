package com.yiku.ptzcontrol

import android.content.Context
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yiku.ptzcontrol.utils.RtspPlayer

class FloatingWindowManager(private val context: Context) {

    private val TAG = "FloatingWindowDebug"
    private var floatingView: View? = null
    private var floatingPlayer1: ExoPlayer? = null
    private var floatingPlayer2: ExoPlayer? = null
    private var playerView1: PlayerView? = null
    private var playerView2: PlayerView? = null

    // 新增视图引用
    private var player1Container: FrameLayout? = null
    private var player2Container: FrameLayout? = null
    private var mainContainer: LinearLayout? = null
    private var videoContainer: LinearLayout? = null
    private var closePlayer1Btn: ImageButton? = null
    private var closePlayer2Btn: ImageButton? = null

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
        streamUrl1: String?,
        streamUrl2: String?,
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

        try {
            // 创建悬浮窗视图
            floatingView = LayoutInflater.from(context).inflate(R.layout.floating_layout, null).apply {
                // 保存视图引用
                mainContainer = findViewById(R.id.mainContainer)
                videoContainer = findViewById(R.id.videoContainer)
                player1Container = findViewById(R.id.player1Container)
                player2Container = findViewById(R.id.player2Container)
                closePlayer1Btn = findViewById(R.id.closePlayer1)
                closePlayer2Btn = findViewById(R.id.closePlayer2)
                playerView1 = findViewById(R.id.floatingPlayer1)
                playerView2 = findViewById(R.id.floatingPlayer2)

                // 初始化视图状态
                updatePlayerViews(streamUrl1 != null, streamUrl2 != null)

                // 创建播放器（如果有流）
                if (streamUrl1 != null) {
                    floatingPlayer1 = RtspPlayer.createIndependentPlayer(
                        context,
                        url = streamUrl1,
                        startPosition = 0,
                        playWhenReady = playWhenReady1
                    ).also { player ->
                        playerView1?.player = player
                    }
                }

                if (streamUrl2 != null) {
                    floatingPlayer2 = RtspPlayer.createIndependentPlayer(
                        context,
                        url = streamUrl2,
                        startPosition = 0,
                        playWhenReady = playWhenReady2
                    ).also { player ->
                        playerView2?.player = player
                    }
                }

                // 关闭整个悬浮窗按钮
                findViewById<ImageButton>(R.id.closeBtn).setOnClickListener {
                    closeFloatingWindow()
                }

                // 关闭播放器1按钮
                closePlayer1Btn?.setOnClickListener {
                    closePlayer(1)
                }

                // 关闭播放器2按钮
                closePlayer2Btn?.setOnClickListener {
                    closePlayer(2)
                }

                // 整个悬浮窗可拖动
                setOnTouchListener(FloatingTouchListener(windowManager))
            }

            // 设置窗口参数 - 改为固定宽度填充屏幕
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, // 宽度改为匹配父容器
                WindowManager.LayoutParams.WRAP_CONTENT, // 高度自适应
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
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

    // 更新播放器视图状态和布局
    private fun updatePlayerViews(player1Active: Boolean, player2Active: Boolean) {
        // 确保视图已初始化
        if (player1Container == null || player2Container == null || videoContainer == null) return
        Log.i(TAG, "player1Container:${player1Container}, player2Container:${player2Container}, videoContainer:${videoContainer}")

        // 设置可见性
        player1Container?.visibility = if (player1Active) View.VISIBLE else View.GONE
        player2Container?.visibility = if (player2Active) View.VISIBLE else View.GONE
        closePlayer1Btn?.visibility = player1Container?.visibility ?: View.GONE
        closePlayer2Btn?.visibility = player2Container?.visibility ?: View.GONE

        // 动态设置布局参数
        val layoutParams1 = player1Container?.layoutParams as? LinearLayout.LayoutParams
        val layoutParams2 = player2Container?.layoutParams as? LinearLayout.LayoutParams

        when {
            // 双视频模式：各占50%
            player1Active && player2Active -> {
                layoutParams1?.apply {
                    width = 0
                    weight = 1f
                }
                layoutParams2?.apply {
                    width = 0
                    weight = 1f
                }
            }

            // 单视频模式：占满宽度
            player1Active -> layoutParams1?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f // 禁用权重
            }

            player2Active -> layoutParams2?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f // 禁用权重
            }
        }

        // 应用参数更新
        player1Container?.layoutParams = layoutParams1
        player2Container?.layoutParams = layoutParams2

        // 更新悬浮窗尺寸和位置
        mainContainer?.post {
            floatingView?.let { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@let
                val displayMetrics = context.resources.displayMetrics

                // 根据活动视频数量调整宽度
                when {
                    player1Active && player2Active -> params.width = WindowManager.LayoutParams.MATCH_PARENT
                    player1Active || player2Active -> {
                        // 单视频时设置宽度为屏幕的 2/5（可按需调整）
                        params.width = (displayMetrics.widthPixels * 0.4).toInt()
                        // 水平居中显示
                        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    }
                }

                // 保持高度自适应
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    // 关闭单个播放器
    private fun closePlayer(playerNum: Int) {
        Log.d(TAG, "关闭播放器: $playerNum")

        when (playerNum) {
            1 -> {
                floatingPlayer1?.release()
                floatingPlayer1 = null
                playerView1?.player = null
                playerView1?.visibility = View.GONE
                updatePlayerViews(false, floatingPlayer2 != null)
            }
            2 -> {
                floatingPlayer2?.release()
                floatingPlayer2 = null
                playerView2?.player = null
                playerView2?.visibility = View.GONE
                updatePlayerViews(floatingPlayer1 != null, false)
            }
        }

        // 如果两个播放器都关闭了，关闭整个悬浮窗
        if (floatingPlayer1 == null && floatingPlayer2 == null) {
            Log.d(TAG, "所有播放器已关闭，关闭悬浮窗")
            closeFloatingWindow()
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
            player1Container = null
            player2Container = null
            mainContainer = null
            videoContainer = null
            closePlayer1Btn = null
            closePlayer2Btn = null
            playerView1 = null
            playerView2 = null
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