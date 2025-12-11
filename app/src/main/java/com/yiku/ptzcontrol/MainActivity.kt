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
    private var player1: RtspPlayer? = null
    private var player2: RtspPlayer? = null
    private lateinit var floatingManager: FloatingWindowManager
    private lateinit var playerView1: SurfaceView    // 小窗口
    private lateinit var playerView2: SurfaceView    // 全屏窗口
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

        // 按系统版本决定添加顺序
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+：后添加的覆盖先添加的
            Log.d(TAG, "当前Android版本大于8.0")
            playerView1 = findViewById(R.id.playerView1)
            playerView2 = findViewById(R.id.playerView2)
        } else {
            Log.d(TAG, "当前Android版本小于8.0")
            playerView1 = findViewById(R.id.playerView2)
            playerView2 = findViewById(R.id.playerView1)
        }
        playerView1.translationZ = 10f
        ViewCompat.setTranslationZ(playerView1, 10f) // 兼容旧版本
        val params1 = playerView1.layoutParams as RelativeLayout.LayoutParams
        params1.addRule(RelativeLayout.ALIGN_PARENT_LEFT)  // 左对齐
        params1.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) // 底对齐
        playerView1.layoutParams = params1
        playerView1.requestLayout()  // 触发重绘

        val params2 = playerView2.layoutParams as ViewGroup.LayoutParams
        params2.width = ViewGroup.LayoutParams.MATCH_PARENT
        params2.height = ViewGroup.LayoutParams.MATCH_PARENT
        playerView2.layoutParams = params2


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

        playerView1.setOnClickListener {
            lifecycleScope.launch {
                switchStreamsSafely()
            }
        }
    }

    /**
     * 预加载另一个流
     */
    private fun preloadStream() {
        if (isPreloading) return

        thread {
            isPreloading = true
            val urlToPreload = if (isExchangePlayer) streamUrl1 else streamUrl2

            // 模拟预加载：提前初始化解码器但不开始播放
            Log.d(TAG, "预加载流: $urlToPreload")

            // 这里可以提前探测流信息并缓存
            preloadedUrl = urlToPreload
            isPreloading = false
        }
    }

    // 在适当的时机调用预加载，比如应用启动后或空闲时
    private fun schedulePreload() {
        Handler(Looper.getMainLooper()).postDelayed({
            preloadStream()
        }, 5000) // 5秒后开始预加载
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

        Log.d(TAG, "playerView1.holder.surface.isValid1=${playerView1.holder.surface.isValid}")
        // 确保SurfaceView已准备好
        if (playerView1.holder.surface.isValid && playerView2.holder.surface.isValid) {
            if (player1 == null || player2 == null) {
                releasePlayers()
                initPlayer()
            }
        } else {
            // 延迟初始化等待Surface准备好
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "playerView1.holder.surface.isValid2=${playerView1.holder.surface.isValid}")
                if (player1 == null || player2 == null) {
                    releasePlayers()
                    initPlayer()
                }
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    private fun initPlayer() {

        // 创建播放器2
        player2 = RtspPlayer(streamUrl2, playerView2, object : RtspPlayer.RtspPlayerEventListener {
            override fun onPlaying() {
//                showToast("开始播放")
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    val params = playerView1.layoutParams as ViewGroup.LayoutParams

                    params.width = 384
                    if (isExchangePlayer) {
                        params.height = 308
                    } else {
                        params.height = 216
                    }
                    playerView1.layoutParams = params
                }
            }

            override fun onStopped() {
//                showToast("播放停止")
            }

            override fun onError(errorMessage: String) {
//                showToast("错误: $errorMessage")
            }

            override fun onLogMessage(message: String) {
//                Log.d("RtspPlayer", message)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                // 可选的视频尺寸变化处理
            }

            override fun onFrameRendered(frameCount: Int) {
                // 可选的帧渲染回调
            }
        })
        Thread.sleep(100)
        // 创建播放器1
        player1 = RtspPlayer(streamUrl1, playerView1, object : RtspPlayer.RtspPlayerEventListener {
            override fun onPlaying() {
//                showToast("开始播放")
            }

            override fun onStopped() {
//                showToast("播放停止")
            }

            override fun onError(errorMessage: String) {
//                showToast("错误: $errorMessage")
            }

            override fun onLogMessage(message: String) {
//                Log.d("RtspPlayer", message)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                // 可选的视频尺寸变化处理
            }

            override fun onFrameRendered(frameCount: Int) {
                // 可选的帧渲染回调
//                Log.d(TAG, "渲染")
            }
        })
    }

    /**
     * 安全的视频流切换方法
     */
    // 确保在协程作用域内调用
    private suspend fun switchStreamsSafely() {
        Log.d(TAG, "开始并行切换视频流")

        if (!playerView1.holder.surface.isValid || !playerView2.holder.surface.isValid) {
            Log.e(TAG, "Surface无效，重新初始化播放器")
            restartPlayers()
            return
        }

        try {
            // 使用 coroutineScope 创建协程作用域
            coroutineScope {
                // 并行暂停两个播放器
                val pauseTask1 = async { player1?.pausePlayback() }
                val pauseTask2 = async { player2?.pausePlayback() }
                pauseTask1.await()
                pauseTask2.await()

                // 交换URL
                val tempUrl = streamUrl1
                streamUrl1 = streamUrl2
                streamUrl2 = tempUrl

                Log.d(TAG, "交换URL: $streamUrl1 <-> $streamUrl2")
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    val params = playerView1.layoutParams as ViewGroup.LayoutParams

                    params.width = 384
                    if (isExchangePlayer) {
                        params.height = 308
                    } else {
                        params.height = 216
                    }
                    playerView1.layoutParams = params
                }

                // 并行切换流
                val switchTask1 = async {
                    player1?.switchStreamOptimized(streamUrl1, false)
                }
                val switchTask2 = async {
                    player2?.switchStreamOptimized(streamUrl2, false)
                }
                switchTask1.await()
                switchTask2.await()

                isExchangePlayer = !isExchangePlayer

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "画面切换完成", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "视频流并行切换完成")
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

        // 释放旧播放器
        player1?.release()
        player2?.release()
        player1 = null
        player2 = null

        // 重新创建播放器
        initPlayer()
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
        player1?.release()
        player1 = null
        player2?.release()
        player2 = null
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