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
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import com.yiku.ptzcontrol.utils.RtspPlayer
import kotlin.math.sqrt

class FloatingWindowManager(private val context: Context) {

    private val TAG = "FloatingWindowManager"
    private var floatingView: View? = null
    private var playerBox: LinearLayout? = null
    private var rtspPlayer: RtspPlayer? = null

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

    fun showFloatingWindow(streamUrl: String, isExchangePlayer: Boolean) {
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
                playerBox = findViewById(R.id.floatingPlayerBox)

                // 创建播放器（如果有流）
                if (streamUrl != "") {
                    Log.d(TAG, "播放视频流: $streamUrl")
                    if(rtspPlayer == null) {
                        // 创建播放器1
                        rtspPlayer = RtspPlayer(context, object : RtspPlayer.RtspPlayerEventListener {
                            override fun onPlaying() {
                            }

                            override fun onStopped() {
                            }

                            override fun onError(errorMessage: String) {
                                Log.e(TAG, errorMessage)
                                rtspPlayer?.clearAllStreams()
                                Thread.sleep(200)
                                rtspPlayer?.addStream(streamUrl, playerBox!!)
                                Thread.sleep(100)
                                rtspPlayer?.playAllStreams()
                            }

                            override fun onLogMessage(message: String) {
                            }

                            override fun onVideoSizeChanged(width: Int, height: Int) {
                                // 可选的视频尺寸变化处理
                            }

                            override fun onFrameRendered(frameCount: Int) {
                                // 可选的帧渲染回调
                            }
                        })
                        Thread.sleep(100)
                        rtspPlayer?.addStream(streamUrl, playerBox!!)
                        Thread.sleep(100)
                    }
                    rtspPlayer?.playAllStreams()

                    findViewById<ConstraintLayout>(R.id.rootLayout).visibility = View.VISIBLE
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
                640, // 自适应宽度
                if(isExchangePlayer) 360 else 512, // 高度自适应
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
        return floatingView != null &&
                rtspPlayer != null
    }

    // 关闭悬浮窗
    fun closeFloatingWindow() {
        Log.d(TAG, "准备关闭悬浮窗")
        floatingView?.let {
            try {
                rtspPlayer?.release()
                rtspPlayer = null

                // 再移除视图
                floatingView?.let {
                    windowManager.removeView(it)
                    it.setBackgroundResource(0) // 清除资源引用
                }
            } catch (e: Exception) {
                Log.e(TAG, "关闭悬浮窗错误: ${e.message}")
            } finally {
                // 确保所有引用置空
                playerContainer = null
                videoContainer = null
                floatingView = null
                Log.d(TAG, "悬浮窗资源完全释放")
            }
        }
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
    private var lastRawX = 0f
    private var lastRawY = 0f

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

                // 关键修复：使用 event.rawX 和 event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY

                activePointerId = event.getPointerId(0)
                isDragging = false
                isScaling = false

                // 检查是否点击在按钮上
                if (isInsideView(event.rawX, event.rawY, exitButton) ||
                    isInsideView(event.rawY, event.rawY, closeButton)) {
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
                    val scaleFactor = currentDistance / initialTouchDistance

                    val newWidth = (initialWidth * scaleFactor).toInt().coerceIn(150, 2000)
                    val newHeight = (initialHeight * scaleFactor).toInt().coerceIn(150, 2000)
                    updateViewLayout(newWidth, newHeight)

                    val newMidPoint = getMidPoint(event)
                    val dx = newMidPoint.x - lastMidPoint.x
                    val dy = newMidPoint.y - lastMidPoint.y

                    val params = view.getParams().apply {
                        x = (x - dx).toInt()
                        y = (y - dy).toInt()
                    }
                    windowManager.updateViewLayout(view, params)
                    lastMidPoint = newMidPoint
                    return true
                }
                // 拖动模式（单指）- 使用相对位移计算
                else if (activePointerId != MotionEvent.INVALID_POINTER_ID && !isScaling) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex == -1) return true

                    // 关键修复：使用 rawX 和 rawY 计算位移增量
                    val currentRawX = event.rawX
                    val currentRawY = event.rawY

                    val dx = currentRawX - lastRawX
                    val dy = currentRawY - lastRawY

                    if (!isDragging) {
                        isDragging = sqrt(dx * dx + dy * dy) >= touchSlop
                    }

                    if (isDragging) {
                        val params = view.getParams()
                        params.x = (params.x + dx).toInt()
                        params.y = (params.y + dy).toInt()

                        // 边界检查
                        val displayMetrics = view.context.resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - view.width
                        val maxY = displayMetrics.heightPixels - view.height

                        params.x = params.x.coerceIn(0, maxX)
                        params.y = params.y.coerceIn(0, maxY)

                        windowManager.updateViewLayout(view, params)
                    }

                    // 关键：更新最后触摸位置
                    lastRawX = currentRawX
                    lastRawY = currentRawY
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)

                if (pointerId == activePointerId) {
                    // 切换主动指针
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newPointerIndex)

                    // 更新最后触摸位置
                    val newPointerIdx = event.findPointerIndex(activePointerId)
                    if (newPointerIdx != -1) {
                        // 使用新指针的 rawX/rawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                }

                if (event.pointerCount < 2) {
                    isScaling = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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