package com.yiku.ptzcontrol
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.yiku.ptzcontrol.service.BaseService
import com.yiku.ptzcontrol.service.BaseService.OnDataReceivedListener
import com.yiku.ptzcontrol.service.C12Service
import com.yiku.ptzcontrol.utils.CommonMethods
import com.yiku.ptzcontrol.utils.RtspPlayer
import kotlin.math.abs


@UnstableApi
class MainActivity : AppCompatActivity(), RtspPlayer.PlayerErrorListener {
    companion object {
        const val OVERLAY_PERMISSION_CODE = 1000
        const val DIRECTION_NONE = 0
        const val DIRECTION_UP = 1
        const val DIRECTION_DOWN = 2
        const val DIRECTION_LEFT = 3
        const val DIRECTION_RIGHT = 4
        private const val TRIGGER_INTERVAL = 100L // 触发间隔(毫秒)
    }

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
    private var dragOverlay: View? = null
    private var startX: Float = 0f
    private var startY: Float = 0f
    private val MIN_DISTANCE: Int = 100 // 最小拖拽距离（像素）
    private val triggerHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var isStopDrag = false
    private var currentDirection: Int = 0
    private var triggerRunnable: Runnable? = null
    private lateinit var prefs: SharedPreferences
    private var streamUrl1 = ""
    private var streamUrl2 = ""
    private var currentFilterPosition = 0
    private lateinit var filterMenu: LinearLayout
    private lateinit var filterListView: ListView
    private var pitchState = 0 // 俯仰状态，0：未到限位，1：已达上限位，2：已达下限位
    private var yawState = 0  // 偏航状态，0：未到限位，1：已达左限位，2：已达右限位

    private val errorRetryCounters = mutableMapOf<Int, Int>() // playerId -> retry count
    private val MAX_RETRY_COUNT = 20 // 最大重试次数
    private val RETRY_DELAY = 3000L // 重试延迟时间 (毫秒)
    private val mainHandler = Handler(Looper.getMainLooper())

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // 隐藏标题栏（如果存在）
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.main)

        Log.d(TAG, "onCreate")
        if(::floatingManager.isInitialized) {
            Log.d(TAG, "onCreate，floatingView：${floatingManager.isFloatingWindowShowing()}")
        }

        prefs = getSharedPreferences("camera_settings", MODE_PRIVATE)

        streamUrl1 = prefs.getString("stream_url_1", "rtsp://192.168.144.108:554/stream=1")!!
        streamUrl2 = prefs.getString("stream_url_2", "rtsp://192.168.144.108:555/stream=2")!!
        host = prefs.getString("ip_address", "192.168.144.108")!!

        service = C12Service()
        service.connect(host)

        // 初始化播放器
        player1 = RtspPlayer.createPlayer(
            this,
            findViewById(R.id.playerView1),
            streamUrl1,
            playerId = 1,
            errorListener = this
        )
        player2 = RtspPlayer.createPlayer(
            this,
            findViewById(R.id.playerView2),
            streamUrl2,
            playerId = 2,
            errorListener = this
        )

        floatingManager = FloatingWindowManager(this)

        // 启动时检查悬浮窗权限
        checkOverlayPermission()

        // 设置按钮点击事件
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            isSetting = true
            openSettingsActivity()
        }
        // 云台回中按钮点击事件
        findViewById<ImageButton>(R.id.ptzToCenterButton).setOnClickListener {
            Toast.makeText(this, "云台回中", Toast.LENGTH_SHORT).show()
            service.yawAndPitchToCenter()
        }

        dragOverlay = findViewById(R.id.dragOverlay);
        setupDragListener();

        // 初始化伪彩按钮和菜单
        filterMenu = findViewById(R.id.filterMenu)
        filterListView = findViewById(R.id.filterListView)

        // 设置伪彩选择按钮
        findViewById<ImageButton>(R.id.filterButton).setOnClickListener {
            toggleFilterMenu()
        }

        // 设置伪彩菜单
        setupFilterMenu()
        // 获取并设置伪彩默认选中项
        service.getPseudoColor(object: BaseService.OnDataReceivedListener {
            override fun onDataReceived(data: String) {
                runOnUiThread {
                    val newPosition = service.colorList.indexOf(data)
                    if (newPosition != -1) {
                        currentFilterPosition = newPosition
                    }

                    // 刷新菜单确保选中状态正确
                    (filterListView.adapter as? BaseAdapter)?.notifyDataSetChanged()

                    Log.d(TAG, "当前伪彩模式设置为: ${service.colorList[currentFilterPosition]}")
                }
                // 收到伪彩信息后，才发送请求，请求推送云台角度数据
                service.ptzAnglePush(true)
                setDataReceivedListener()
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "获取伪彩失败: $error", Toast.LENGTH_SHORT).show()
                }
            }

        })

        // 添加全局点击监听（点击菜单外区域关闭菜单）
        findViewById<View>(R.id.dragOverlay).setOnClickListener {
            hideFilterMenu()
        }

        // 悬浮窗显示
        findViewById<ImageButton>(R.id.openFloatingWindowButton).setOnClickListener {
            this.moveTaskToBack(true)
        }

        val smallContainer = findViewById<FrameLayout>(R.id.smallVideoContainer)
        smallContainer.post {
            smallContainer.bringToFront()
            smallContainer.invalidate()
        }
    }

    private fun setDataReceivedListener() {
        val listener = object : OnDataReceivedListener {
            override fun onDataReceived(data: String) {
                // 云台数据
                if(data.contains("#tpUGCrGAC")) {
                    if (data.length < 24) {
                        return
                    }
                    val yawStateStr = data.substring(10, 14)
                    val pitchStateStr = data.substring(14, 18)
                    val yawStateNum = CommonMethods.hexToSignedInt(yawStateStr)
                    val pitchStateNum = CommonMethods.hexToSignedInt(pitchStateStr)

                    Log.d(TAG, "yawStateStr=${yawStateNum}")
                    Log.d(TAG, "pitchStateStr=${pitchStateNum}")

                    yawState = if(yawStateNum >= 9000) {
                        1
                    } else if(yawStateNum <= -9000) {
                        2
                    } else {
                        0
                    }

                    pitchState = if(pitchStateNum >= 3000) {
                        1
                    } else if(pitchStateNum <= -9000) {
                        2
                    } else {
                        0
                    }
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, error)
            }
        }

        // 注册临时监听器并发送命令
        service.setGlobalListener(listener)
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

    @OptIn(UnstableApi::class)
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        val newHost = prefs.getString("ip_address", "192.168.144.108")!!
        // 如果ip变了，断开重连
        if(newHost != host) {
            service.disconnect()
            host = newHost
            service.connect(host)
        }
        val newStreamUrl1 = prefs.getString("stream_url_1", "rtsp://192.168.144.108:554/stream=1")!!
        val newStreamUrl2 = prefs.getString("stream_url_2", "rtsp://192.168.144.108:555/stream=2")!!
        var isUrlChange = false
        if(newStreamUrl1 != streamUrl1 || newStreamUrl2 != streamUrl2) {
            streamUrl1 = newStreamUrl1
            streamUrl2 = newStreamUrl2
            isUrlChange = true
        }

        val resetPlayer1 = RtspPlayer.checkPlayer(player1)
        val resetPlayer2 = RtspPlayer.checkPlayer(player2)
        // 如果有一个播放器延迟超过1.5s，或者视频URL有改动，释放并重新创建播放器
        if (resetPlayer1 || resetPlayer2 || isUrlChange) {
            // 释放播放器
            player1.release()
            player2.release()
            // 初始化播放器
            player1 = RtspPlayer.createPlayer(
                this,
                findViewById(R.id.playerView1),
                streamUrl1,
                playerId = 1,
                errorListener = this
            )
            player2 = RtspPlayer.createPlayer(
                this,
                findViewById(R.id.playerView2),
                streamUrl2,
                playerId = 2,
                errorListener = this
            )
        }
        else {
            // 回到前台时恢复播放
            player1.play()
            player2.play()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")

        // 保存播放状态
        player1PlayWhenReady = player1.playWhenReady
        player2PlayWhenReady = player2.playWhenReady
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")

        if (!isChangingConfigurations && !isSetting && !isFinishing) {
            // 保存播放状态
            player1PlayWhenReady = player1.playWhenReady
            player2PlayWhenReady = player2.playWhenReady

            // 暂停播放器
            player1.pause()
            player2.pause()

            // 尝试显示悬浮窗
            if (floatingManager.checkOverlayPermission()) {
                floatingManager.showFloatingWindow(
                    streamUrl = streamUrl2,
                    playWhenReady = player2PlayWhenReady
                )
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
        service.ptzAnglePush(false)
        service.disconnect()
        // 确保关闭悬浮窗
        floatingManager.closeFloatingWindow()
        // 停止所有后台任务
        triggerHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
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
                    isStopDrag = false
                    currentDirection = DIRECTION_NONE
                    // 处理点击事件开始
                    v.performClick()
                    return@OnTouchListener true
                }

                MotionEvent.ACTION_MOVE -> {
                    if(isStopDrag) {
                        return@OnTouchListener true
                    }
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
                        stopTrigger()
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
        if (!isDragging) return
        when (direction) {
            DIRECTION_UP -> {
                if(pitchState == 1) {
                    isStopDrag = true
                    stopTrigger()
                    Log.i(TAG, "俯仰已达限位")
                    Toast.makeText(this, "俯仰已达限位", Toast.LENGTH_SHORT).show()
                    return
                }
                service.turnUpwards()
            }
            DIRECTION_DOWN -> {
                if(pitchState == 2) {
                    isStopDrag = true
                    stopTrigger()
                    Log.i(TAG, "俯仰已达限位")
                    Toast.makeText(this, "俯仰已达限位", Toast.LENGTH_SHORT).show()
                    return
                }
                service.turnDownwards()
            }
            DIRECTION_LEFT -> {
                if(yawState == 1) {
                    isStopDrag = true
                    stopTrigger()
                    Log.i(TAG, "偏航已达限位")
                    Toast.makeText(this, "偏航已达限位", Toast.LENGTH_SHORT).show()
                    return
                }
                service.turnLeft()
            }
            DIRECTION_RIGHT -> {
                if(yawState == 2) {
                    isStopDrag = true
                    stopTrigger()
                    Log.i(TAG, "偏航已达限位")
                    Toast.makeText(this, "偏航已达限位", Toast.LENGTH_SHORT).show()
                    return
                }
                service.turnRight()
            }
        }
    }
    private fun stopTrigger() {
        isDragging = false
        triggerRunnable?.let { triggerHandler.removeCallbacks(it) }
        triggerRunnable = null
        currentDirection = DIRECTION_NONE
    }

    // 初始化伪彩设置菜单
    private fun setupFilterMenu() {
        filterListView.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            service.colorList
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView

                // 设置文本样式
                view.setTextColor(Color.WHITE)
                view.textSize = 16f
                view.setPadding(32, 24, 32, 24)

                // 高亮当前选中项
                if (position == currentFilterPosition) {
                    view.setBackgroundResource(R.drawable.filter_item_selected)
                } else {
                    view.background = null
                }

                return view
            }
        }

        filterListView.setOnItemClickListener { _, _, position, _ ->
            currentFilterPosition = position
            applyFilterEffect(service.colorList[position])
            hideFilterMenu()

            // 刷新列表更新选中状态
            (filterListView.adapter as BaseAdapter).notifyDataSetChanged()
        }
    }

    private fun toggleFilterMenu() {
        if (filterMenu.visibility == View.VISIBLE) {
            hideFilterMenu()
        } else {
            showFilterMenu()
        }
    }

    private fun showFilterMenu() {
        filterMenu.visibility = View.VISIBLE
        // 刷新列表确保选中状态正确
        (filterListView.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun hideFilterMenu() {
        filterMenu.visibility = View.GONE
    }

    private fun applyFilterEffect(colorName: String) {
        service.setPseudoColor(colorName)
    }

    override fun onPlayerError(playerId: Int, exception: Exception) {
        runOnUiThread {
            val message = when (playerId) {
                1 -> "可见光视频播放失败"
                2 -> "热成像视频播放失败"
                else -> "视频播放异常"
            }

//            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.e(TAG, "Player $playerId error", exception)

            // 自动重试逻辑
            val retryCount = errorRetryCounters.getOrDefault(playerId, 0)
            if (retryCount < MAX_RETRY_COUNT) {
                Toast.makeText(this, "$message, 将在3秒后尝试重连...", Toast.LENGTH_SHORT).show()
                errorRetryCounters[playerId] = retryCount + 1

                mainHandler.postDelayed({
                    // 重连前检查Activity状态
                    if (!isFinishing && !isDestroyed) {
                        tryReloadPlayer(playerId)
                    }
                }, RETRY_DELAY)
            } else {
                Toast.makeText(this, "重连失败，请检查网络或设置", Toast.LENGTH_LONG).show()
                // 重置重试计数器
                errorRetryCounters[playerId] = 0
            }
        }
    }
    // 重试加载播放器
    private fun tryReloadPlayer(playerId: Int) {
        if (isFinishing || isDestroyed) {
            Log.d(TAG, "Activity已销毁，取消重连播放器$playerId")
            return
        }
        try {
            when (playerId) {
                1 -> {
                    player1.release()
                    player1 = RtspPlayer.createPlayer(
                        this,
                        findViewById(R.id.playerView1),
                        streamUrl1,
                        playerId = 1,
                        errorListener = this
                    )
                }
                2 -> {
                    player2.release()
                    player2 = RtspPlayer.createPlayer(
                        this,
                        findViewById(R.id.playerView2),
                        streamUrl2,
                        playerId = 2,
                        errorListener = this
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "重载播放器失败", e)
        }
    }
}