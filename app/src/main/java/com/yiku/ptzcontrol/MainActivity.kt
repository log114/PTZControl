package com.yiku.ptzcontrol
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.yiku.ptzcontrol.service.BaseService
import com.yiku.ptzcontrol.service.ZT6Service
import com.yiku.ptzcontrol.utils.MsgCallback
import com.yiku.ptzcontrol.utils.RtspPlayer
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    companion object {
        const val OVERLAY_PERMISSION_CODE = 1000
        const val DIRECTION_NONE = 0
        const val DIRECTION_UP = 1
        const val DIRECTION_DOWN = 2
        const val DIRECTION_LEFT = 3
        const val DIRECTION_RIGHT = 4
        private const val TRIGGER_INTERVAL = 100L // 触发间隔(毫秒)
    }

    private var isFirst = true
    private var player: RtspPlayer? = null
    private lateinit var floatingManager: FloatingWindowManager
    private lateinit var playerBox1: LinearLayout    // 小窗口
    private lateinit var playerBox2: LinearLayout    // 全屏窗口
    private var isConnecting: Boolean = false
    private var isFirstConnect: Boolean = true

    private var isSetting: Boolean = false
    // 调试标签
    private val TAG = "MainActivityDebug"
    private var host = ""
    private lateinit var service: BaseService
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
    private var _context: Context = this

    // 添加预加载状态变量
    private var isPreloading = false
    private var preloadedUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isExchangePlayer = false

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // 隐藏标题栏（如果存在）
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.main)
        playerBox1 = findViewById(R.id.playerBox1)
        playerBox2 = findViewById(R.id.playerBox2)

        prefs = getSharedPreferences("camera_settings", MODE_PRIVATE)

        streamUrl1 = prefs.getString("stream_url_1", "rtsp://192.168.144.25:8554/video1")!!
        streamUrl2 = prefs.getString("stream_url_2", "rtsp://192.168.144.25:8554/video2")!!
        host = prefs.getString("ip_address", "192.168.144.25")!!

        service = ZT6Service()
        service.registMsgCallback(object : MsgCallback {
            override fun getId(): String {
                return "ThrowerWeightCallback"
            }
            override fun onMsg(msg: ByteArray) {
                if (msg[0] != 0x55.toByte() || msg[1] != 0x66.toByte()) {
                    return
                }
                // 伪彩
                if (msg[7] == 0x1A.toByte()) {
                    onColorDataReceived(msg)
                }
            }

        })
        setConnectState()

        initPlayer()

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

        // 添加全局点击监听（点击菜单外区域关闭菜单）
        findViewById<View>(R.id.dragOverlay).setOnClickListener {
            hideFilterMenu()
        }

        // 悬浮窗显示
        findViewById<ImageButton>(R.id.openFloatingWindowButton).setOnClickListener {
            this.moveTaskToBack(true)
            // 尝试显示悬浮窗
            if (floatingManager.checkOverlayPermission()) {
                floatingManager.showFloatingWindow(streamUrl2, isExchangePlayer)
            } else {
                Log.d(TAG, "Floating window permission not granted")
            }
        }

        // 放大
        findViewById<ImageButton>(R.id.enlargeButton).setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    service.zoom("enlarge")
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    service.zoom("stop")
                    true // 消费抬起事件
                }
                else -> false
            }
        }

        // 缩小
        findViewById<ImageButton>(R.id.reduceButton).setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    service.zoom("reduce")
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    service.zoom("stop")
                    true // 消费抬起事件
                }
                else -> false
            }
        }

        playerBox1.setOnClickListener {
            lifecycleScope.launch {
                switchStreamsSafely()
            }
        }
    }

    // 设置伪彩默认选中项
    private fun onColorDataReceived(data: ByteArray) {
        val colorCode = data[8].toInt()
        val colorStr = service.convertPseudoColorInt2Str(colorCode)
        runOnUiThread {
            val newPosition = service.colorList.indexOf(colorStr)
            if (newPosition != -1) {
                currentFilterPosition = newPosition
            }

            // 刷新菜单确保选中状态正确
            (filterListView.adapter as? BaseAdapter)?.notifyDataSetChanged()

            Log.d(TAG, "当前伪彩模式设置为: ${service.colorList[currentFilterPosition]}")
        }
        // 收到伪彩信息后，才发送请求，请求推送云台角度数据
//        service.ptzAnglePush(true)
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        isSetting = false

        Log.d(TAG, "start的时候，floatingView：${floatingManager.isFloatingWindowShowing()}")

        // 关闭悬浮窗
        floatingManager.closeFloatingWindow()
        Log.d(TAG, "悬浮窗关闭完成")

        val newHost = prefs.getString("ip_address", "192.168.144.25")!!
        // 如果ip变了，断开重连
        if(newHost != host) {
            service.disconnect()
            host = newHost
            service.connect(host)
        }
        val newStreamUrl1 = prefs.getString("stream_url_1", "rtsp://192.168.144.25:8554/video1")!!
        val newStreamUrl2 = prefs.getString("stream_url_2", "rtsp://192.168.144.25:8554/video2")!!
        if(newStreamUrl1 != streamUrl1 || newStreamUrl2 != streamUrl2) {
            if(isExchangePlayer) {
                streamUrl1 = newStreamUrl2
                streamUrl2 = newStreamUrl1
            }
            else {
                streamUrl1 = newStreamUrl1
                streamUrl2 = newStreamUrl2
            }
        }

        if(isFirst) {
            isFirst = false
            return
        }

        if (player == null) {
            releasePlayers()
            initPlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    private fun initPlayer() {
        // 创建播放器
        player = RtspPlayer(this, object : RtspPlayer.RtspPlayerEventListener {
            override fun onPlaying() {
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    val params = playerBox1.layoutParams as ViewGroup.LayoutParams

                    params.width = dpToPx(256f).toInt()
                    if (isExchangePlayer) {
                        params.height = dpToPx(205f).toInt()
                    } else {
                        params.height = dpToPx(144f).toInt()
                    }
                    playerBox1.layoutParams = params
                }
            }

            override fun onStopped() {
                showToast("播放停止")
            }

            override fun onError(errorMessage: String) {
//                showToast("错误: $errorMessage")
                player?.clearAllStreams()
                Thread.sleep(200)
                player?.addStream(streamUrl2, playerBox2)
                player?.addStream(streamUrl1, playerBox1)
                Thread.sleep(100)
                player?.playAllStreams()
            }

            override fun onLogMessage(message: String) {
                Log.d("RtspPlayer", message)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                // 可选的视频尺寸变化处理
            }

            override fun onFrameRendered(frameCount: Int) {
                // 可选的帧渲染回调
            }
        })
        Thread.sleep(100)
        player?.addStream(streamUrl1, playerBox1)
        player?.addStream(streamUrl2, playerBox2)
        Thread.sleep(100)
        player?.playAllStreams()
    }

    /**
     * 安全的视频流切换方法
     */
    // 确保在协程作用域内调用
    private suspend fun switchStreamsSafely() {
        Log.d(TAG, "开始安全切换视频流")

        try {
            coroutineScope {
                // 1. 释放旧播放器资源
                player?.clearAllStreams()
                delay(200) // 关键：给SurfaceView一些时间完成生命周期回调

                // 3. 交换URL
                val tempUrl = streamUrl1
                streamUrl1 = streamUrl2
                streamUrl2 = tempUrl
                isExchangePlayer = !isExchangePlayer

                // 4. 重新加载播放内容
                player?.addStream(streamUrl2, playerBox2)
                player?.addStream(streamUrl1, playerBox1)

                // 5. 重新播放内容
                Thread.sleep(100)
                player?.playAllStreams()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "画面切换完成", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "视频流安全切换完成")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换视频流异常: ${e.message}")
            withContext(Dispatchers.Main) {
                restartPlayers()
            }
        }
    }

    /**
     * 完全重启播放器
     */
    private fun restartPlayers() {
        Log.d(TAG, "完全重启播放器")

        // 确保在IO线程执行耗时操作
        lifecycleScope.launch(Dispatchers.IO) {
            player?.release()
            // 给底层资源一些清理时间
            delay(100)

            withContext(Dispatchers.Main) {
                player = null
                initPlayer()
            }
        }
    }

    private fun showToast(msg: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(
                this, msg, Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // 释放播放器资源
        releasePlayers()
//        service.ptzAnglePush(false)
        service.disconnect()
        // 确保关闭悬浮窗
        floatingManager.closeFloatingWindow()
        // 停止所有后台任务
        triggerHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "onDestroy，floatingView：${floatingManager.isFloatingWindowShowing()}")
    }

    private fun releasePlayers() {
        player?.release()
        player = null
        Log.i(TAG, "播放器释放完成")
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
        service.stopMove()
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

    // dp转px工具方法
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    // 定时器，判断连接状态
    private fun setConnectState() {
        val timer = Timer();
        val task = object : TimerTask() {
            override fun run() {
                if(service.getIsConnected()){
                    service.heartbeat()
                }
                else if(!isConnecting){
                    isConnecting = true
                    // 尝试重连
                    thread {
                        if(!isFirstConnect) {
                            Thread.sleep(10000)
                        }
                        isFirstConnect = false
                        while (!service.connect(host)) {
                            Thread.sleep(1000)
                        }
                        isConnecting = false
                    }
                }
            }
        }
        // 定时器，100毫秒后开始执行，每1秒执行一次
        timer.scheduleAtFixedRate(task, 100, 1000);
    }
}