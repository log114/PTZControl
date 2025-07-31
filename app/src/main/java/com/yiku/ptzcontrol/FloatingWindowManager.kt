package com.yiku.ptzcontrol

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.abs
import kotlin.math.sqrt

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
                setBackgroundColor(Color.TRANSPARENT)
                // 获取按钮引用
                val exitBtn = findViewById<ImageButton>(R.id.exitBtn)
                val closeBtn = findViewById<ImageButton>(R.id.closeBtn)

                // 设置触摸监听器（传递必要参数）
                setOnTouchListener(FloatingTouchListener(
                    windowManager = windowManager,
                    view = this,
                    exitButton = exitBtn,
                    closeButton = closeBtn
                ))

                // 设置最小尺寸
                val displayMetrics = context.resources.displayMetrics
                minimumWidth = (150 * displayMetrics.density).toInt()
                minimumHeight = (150 * displayMetrics.density).toInt()

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
                exitBtn.setOnClickListener {
                    openMainApp()
                }

                // 关闭整个悬浮窗按钮
                closeBtn.setOnClickListener {
                    closeFloatingWindow()
                }
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
class FloatingTouchListener(
    private val windowManager: WindowManager,
    private val view: View,
    private val exitButton: View,
    private val closeButton: View
) : View.OnTouchListener {
    private val TAG = "FloatingWindowDebug"
    private var initialX = 0
    private var initialY = 0
    private var initialRawX = 0f
    private var initialRawY = 0f

    // 缩放相关变量
    private var initialTouchDistance = 0f
    private var initialWidth = 0
    private var initialHeight = 0
    private var isScaling = false
    private var isDragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastMidPoint = PointF()

    private val touchSlop by lazy {
        ViewConfiguration.get(view.context).scaledTouchSlop
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val params = view.getParams()
                initialX = params.x
                initialY = params.y
                initialRawX = event.rawX
                initialRawY = event.rawY
                activePointerId = event.getPointerId(0)
                initialWidth = view.width
                initialHeight = view.height
                isDragging = false
                isScaling = false

                // 检查是否点击在按钮上
                if (isInsideView(event.rawX, event.rawY, exitButton) ||
                    isInsideView(event.rawX, event.rawY, closeButton)) {
                    return false
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 检测到双指触摸
                if (event.pointerCount == 2 && !isScaling) {
                    val distance = getDistance(event)
                    initialTouchDistance = distance
                    initialWidth = view.width
                    initialHeight = view.height
                    isScaling = true
                    isDragging = false

                    // 获取两指中心点
                    lastMidPoint = getMidPoint(event)

                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 缩放模式
                if (isScaling && event.pointerCount == 2) {
                    val currentDistance = getDistance(event)

                    // 使用比例而不是固定阈值
                    val scaleFactor = currentDistance / initialTouchDistance

                    // 计算新的宽度和高度 (扩大限制范围)
                    val newWidth = (initialWidth * scaleFactor).toInt().coerceIn(150, 2000)
                    val newHeight = (initialHeight * scaleFactor).toInt().coerceIn(150, 2000)

                    // 更新布局参数
                    updateViewLayout(newWidth, newHeight)

                    // 更新中心点位置
                    val newMidPoint = getMidPoint(event)
                    val dx = newMidPoint.x - lastMidPoint.x
                    val dy = newMidPoint.y - lastMidPoint.y

                    // 移动视图以保持中心点稳定
                    val params = view.getParams().apply {
                        x = (x - dx).toInt()
                        y = (y - dy).toInt()
                    }
                    windowManager.updateViewLayout(view, params)

                    lastMidPoint = newMidPoint
                    return true
                }
                // 拖动模式（单指）
                else if (activePointerId != MotionEvent.INVALID_POINTER_ID && !isScaling) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex == -1) return true

                    val dx = (event.rawX - initialRawX)
                    val dy = (event.rawY - initialRawY)

                    if (!isDragging) {
                        isDragging = sqrt(dx * dx + dy * dy) >= touchSlop
                    }

                    if (isDragging) {
                        val params = view.getParams()
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newPointerIndex)
                    initialRawX = event.getX(newPointerIndex) + view.left
                    initialRawY = event.getY(newPointerIndex) + view.top

                    // 更新初始位置
                    val params = view.getParams()
                    initialX = params.x
                    initialY = params.y

                    // 退出缩放模式
                    if (isScaling) {
                        isScaling = false
                        return true
                    }
                } else if (pointerId != activePointerId) {
                    // 更新初始距离以防止跳跃
                    initialTouchDistance = getDistance(event)
                }
            }

            MotionEvent.ACTION_UP -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
                isScaling = false
            }
        }
        return false
    }

    // 获取视图的布局参数
    private fun View.getParams(): WindowManager.LayoutParams {
        return layoutParams as WindowManager.LayoutParams
    }

    // 计算两指间距离
    private fun getDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    // 获取两指中心点
    private fun getMidPoint(event: MotionEvent): PointF {
        return PointF(
            (event.getX(0) + event.getX(1)) / 2 + view.left,
            (event.getY(0) + event.getY(1)) / 2 + view.top
        )
    }

    // 更新视图布局
    private fun updateViewLayout(width: Int, height: Int) {
        val params = view.getParams()
        params.width = width
        params.height = height
        windowManager.updateViewLayout(view, params)
    }

    // 检查触摸点是否在视图内
    private fun isInsideView(x: Float, y: Float, targetView: View): Boolean {
        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + targetView.width &&
                y >= location[1] && y <= location[1] + targetView.height
    }
}