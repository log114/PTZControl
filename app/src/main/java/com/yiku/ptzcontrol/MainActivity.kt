package com.yiku.ptzcontrol
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.yiku.ptzcontrol.service.BaseService
import com.yiku.ptzcontrol.service.C12Service
import com.yiku.ptzcontrol.utils.YunZhuoCrc
import kotlin.math.abs


class MainActivity : AppCompatActivity() {

    private lateinit var player1: ExoPlayer
    private lateinit var player2: ExoPlayer
    private lateinit var floatingManager: FloatingWindowManager
    private var isSetting: Boolean = false

    // 调试标签
    private val TAG = "MainActivityDebug"
    private var host = ""
    private lateinit var service: BaseService

    // 记录播放器状态
    private var player1PlayWhenReady = true
    private var player2PlayWhenReady = true
    private var player1CurrentPosition = 0L
    private var player2CurrentPosition = 0L
    private var dragOverlay: View? = null
    private var startX: Float = 0f
    private var startY: Float = 0f
    private val MIN_DISTANCE: Int = 100 // 最小拖拽距离（像素）

    private val triggerHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var currentDirection: Int = 0
    private var triggerRunnable: Runnable? = null

    companion object {
        const val OVERLAY_PERMISSION_CODE = 1000
        const val DIRECTION_NONE = 0
        const val DIRECTION_UP = 1
        const val DIRECTION_DOWN = 2
        const val DIRECTION_LEFT = 3
        const val DIRECTION_RIGHT = 4
        private const val TRIGGER_INTERVAL = 100L // 触发间隔(毫秒)
    }

    private lateinit var prefs: SharedPreferences

    private var streamUrl_1 = ""
    private var streamUrl_2 = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        Log.d(TAG, "onCreate")
        if(::floatingManager.isInitialized) {
            Log.d(TAG, "onCreate，floatingView：${floatingManager.isFloatingWindowShowing()}")
        }

        prefs = getSharedPreferences("camera_settings", MODE_PRIVATE)

        streamUrl_1 = prefs.getString("stream_url_1", "rtsp://192.168.144.108:554/stream=0")!!
        streamUrl_2 = prefs.getString("stream_url_2", "rtsp://192.168.144.108:554/stream=1")!!
        host = prefs.getString("ip_address", "192.168.144.108")!!

        service = C12Service()
        service.connect(host)

        // 初始化播放器
        player1 = createPlayer(streamUrl_1, R.id.playerView1)
        player2 = createPlayer(streamUrl_2, R.id.playerView2)

        floatingManager = FloatingWindowManager(this)

        // 启动时检查悬浮窗权限
        checkOverlayPermission()

        // 设置按钮点击事件
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            isSetting = true
            openSettingsActivity()
        }

        // 偏航回中按钮点击事件
        findViewById<ImageButton>(R.id.yawToCEnterButton).setOnClickListener {
            Toast.makeText(this, "偏航回中", Toast.LENGTH_SHORT).show()
            service.yawToCenter()
        }
        // 云台回中按钮点击事件
        findViewById<ImageButton>(R.id.ptzToCenterButton).setOnClickListener {
            Toast.makeText(this, "云台回中", Toast.LENGTH_SHORT).show()
            service.yawAndPitchToCenter()
        }
        // 俯仰朝下按钮点击事件
        findViewById<ImageButton>(R.id.pitchToCenterButton).setOnClickListener {
            Toast.makeText(this, "俯仰朝下", Toast.LENGTH_SHORT).show()
            service.pitchDownwards()
        }

        dragOverlay = findViewById(R.id.dragOverlay);
        setupDragListener();
    }

    // 创建播放器助手函数
    private fun createPlayer(url: String, viewId: Int): ExoPlayer {
        return ExoPlayer.Builder(this).build().apply {
            findViewById<StyledPlayerView>(viewId).player = this
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: ${error.message}")
                }
            })
            prepare()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        isSetting = false

        Log.d(TAG, "start的时候，floatingView：${floatingManager.isFloatingWindowShowing()}")

        // 关闭悬浮窗
        floatingManager.closeFloatingWindow()

        // 恢复播放器状态
        player1.playWhenReady = true
        player2.playWhenReady = true
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        // 回到前台时恢复播放
        player1.play()
        player2.play()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")

        // 保存播放状态
        player1PlayWhenReady = player1.playWhenReady
        player2PlayWhenReady = player2.playWhenReady
        player1CurrentPosition = player1.currentPosition
        player2CurrentPosition = player2.currentPosition
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")

        if (!isChangingConfigurations && !isSetting) {
            // 保存播放状态
            player1PlayWhenReady = player1.playWhenReady
            player2PlayWhenReady = player2.playWhenReady
            player1CurrentPosition = player1.currentPosition
            player2CurrentPosition = player2.currentPosition

            // 暂停播放器
            player1.pause()
            player2.pause()

            // 尝试显示悬浮窗
            if (floatingManager.checkOverlayPermission()) {
                Log.d(TAG, "Attempting to show floating window")
                floatingManager.showFloatingWindow(player1, player2,
                    player1PlayWhenReady, player2PlayWhenReady,
                    player1CurrentPosition, player2CurrentPosition)


                Log.d(TAG, "stop的时候，floatingView：${floatingManager.isFloatingWindowShowing()}")
            } else {
                Log.d(TAG, "Floating window permission not granted")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // 释放播放器资源
        player1.release()
        player2.release()
        Log.d(TAG, "onDestroy，floatingView：${floatingManager.isFloatingWindowShowing()}")
    }

    // 检查悬浮窗权限
    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
        } else {
            Log.d(TAG, "Overlay permission already granted")
        }
    }

    // 处理权限请求结果
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted by user")
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Overlay permission denied by user")
                Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun setupDragListener() {
        dragOverlay!!.setOnTouchListener(OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isDragging = false
                    currentDirection = DIRECTION_NONE
                    // 处理点击事件开始
                    v.performClick()
                    return@OnTouchListener true
                }

                MotionEvent.ACTION_MOVE -> {
                    val endX = event.x
                    val endY = event.y
                    val deltaX = endX - startX
                    val deltaY = endY - startY

                    if (!isDragging && (abs(deltaX) > MIN_DISTANCE || abs(deltaY) > MIN_DISTANCE)) {
                        isDragging = true
                        triggerRunnable?.let { triggerHandler.removeCallbacks(it) }

                        triggerRunnable = object : Runnable {
                            override fun run() {
                                if (isDragging) {
                                    processDirectionEvent(currentDirection)
                                    triggerHandler.postDelayed(this, TRIGGER_INTERVAL)
                                }
                            }
                        }
                        triggerHandler.post(triggerRunnable!!)
                    }

                    if (isDragging) {
                        // 确定拖拽方向
                        if (abs(deltaX) > abs(deltaY)) {
                            if (abs(deltaX) > MIN_DISTANCE) {
                                currentDirection = if (deltaX > 0) DIRECTION_RIGHT else DIRECTION_LEFT
                            }
                        } else {
                            if (abs(deltaY) > MIN_DISTANCE) {
                                currentDirection = if (deltaY > 0) DIRECTION_DOWN else DIRECTION_UP
                            }
                        }
                    }
                    return@OnTouchListener true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        // 停止持续触发
                        isDragging = false
                        triggerRunnable?.let { triggerHandler.removeCallbacks(it) }
                        currentDirection = DIRECTION_NONE
                        Toast.makeText(this, "停止拖拽", Toast.LENGTH_SHORT).show()
                    } else {
                        // 处理点击事件结束
                        v.performClick()
                    }
                    return@OnTouchListener true
                }
            }
            false
        })
    }

    private fun processDirectionEvent(direction: Int) {
        when (direction) {
            DIRECTION_UP -> {
                Log.d(TAG, "持续上移: ${System.currentTimeMillis()}")
                service.turnUpwards()
            }
            DIRECTION_DOWN -> {
                Log.d(TAG, "持续下移: ${System.currentTimeMillis()}")
                service.turnDownwards()
            }
            DIRECTION_LEFT -> {
                Log.d(TAG, "持续左移: ${System.currentTimeMillis()}")
                service.turnLeft()
            }
            DIRECTION_RIGHT -> {
                Log.d(TAG, "持续右移: ${System.currentTimeMillis()}")
                service.turnRight()
            }
        }
    }
}