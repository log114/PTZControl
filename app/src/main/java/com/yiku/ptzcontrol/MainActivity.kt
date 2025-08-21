package com.yiku.ptzcontrol
import android.content.Context
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import com.yiku.ptzcontrol.service.BaseService
import com.yiku.ptzcontrol.service.BaseService.OnDataReceivedListener
import com.yiku.ptzcontrol.service.ZT6Service
import com.yiku.ptzcontrol.utils.CommonMethods
import com.yiku.ptzcontrol.utils.MsgCallback
import com.yiku.ptzcontrol.utils.RtspPlayer
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread
import kotlin.math.abs
import com.yiku.ptzcontrol.utils.PlayerCallback

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
    private var player1: MediaPlayer? = null
    private var player2: MediaPlayer? = null
    private lateinit var floatingManager: FloatingWindowManager
    private lateinit var playerView1: VLCVideoLayout
    private lateinit var playerView2: VLCVideoLayout
    private var isConnecting: Boolean = false
    private var isFirstConnect: Boolean = true

    private var rtspPlayer: RtspPlayer? = null

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

    private val errorRetryCounters = mutableMapOf<Int, Int>() // playerId -> retry count
    private val MAX_RETRY_COUNT = 20 // 最大重试次数
    private val RETRY_DELAY = 3000L // 重试延迟时间 (毫秒)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isExchangePlayer = false

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
        playerView1 = findViewById(R.id.playerView1)
        playerView2 = findViewById(R.id.playerView2)

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
                // 释放掉播放器
                releasePlayers()
            } else {
                Log.d(TAG, "Floating window permission not granted")
            }
        }

        playerView1.setOnClickListener {
            // 交换前暂停播放
            player1?.pause()
            player2?.pause()

            // 交换数据源（非重新创建MediaPlayer）
            val tempMedia = player1?.media
            player1?.media = player2?.media
            player2?.media = tempMedia

            // 恢复播放
            player1?.play()
            player2?.play()

            // 交换连接，使得退回该页面时，加载的内容与离开前一直
            val tempUrl = streamUrl1
            streamUrl1 = streamUrl2
            streamUrl2 = tempUrl
            isExchangePlayer = !isExchangePlayer
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
//        setDataReceivedListener()
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

    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

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

        if(rtspPlayer != null) {
            releasePlayers() // 确保先释放旧资源
            initPlayer()
        }
    }

    private fun initPlayer() {
        if(rtspPlayer == null) {
            rtspPlayer = RtspPlayer(this)
            rtspPlayer?.registPlayerCallback(object : PlayerCallback {
                override fun onPlaying(index: Int, mediaPlayer: MediaPlayer) {
                    Log.i(TAG, "视频${index}播放成功")
                    when(index) {
                        1 -> {
                            player1 = mediaPlayer
                            val handler = Handler(Looper.getMainLooper())
                            handler.post {
                                val linearLayout =
                                    findViewById<LinearLayout>(R.id.smallVideoContainer)
                                val params = linearLayout.layoutParams as ViewGroup.LayoutParams

                                if (isExchangePlayer) {
                                    params.height = 308
                                } else {
                                    params.height = 216
                                }
                                linearLayout.layoutParams = params
                            }
                        }
                        2 -> {
                            player2 = mediaPlayer
                        }
                    }
                }

                override fun onError(index: Int) {
                    var errorMsg = ""
                    when(index) {
                        1 -> {
                            errorMsg = if(isExchangePlayer) {
                                "热成像视频播放失败"
                            } else {
                                "可见光视频播放失败"
                            }
                        }
                        2 -> {
                            errorMsg = if(isExchangePlayer) {
                                "可见光视频播放失败"
                            } else {
                                "热成像视频播放失败"
                            }
                        }
                    }
                    Log.e(TAG, errorMsg)
                    Toast.makeText(_context, errorMsg, Toast.LENGTH_SHORT).show()
                }
            })
        }
        rtspPlayer?.createPlayer(1, playerView1, streamUrl1)
        rtspPlayer?.createPlayer(2, playerView2, streamUrl2)
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
        if(rtspPlayer != null) {
            rtspPlayer!!.release()
            rtspPlayer = null
        }
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
        // 确保释放顺序：先停止播放，再解除视图绑定，最后释放资源
        if (player1 != null) {
            player1.apply {
                this!!.stop()
                detachViews()
                release()
            }
            player1 = null
        }
        if (player2 != null) {
            player2.apply {
                this!!.stop()
                detachViews()
                release()
            }
            player2 = null
        }
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
    // 重试加载播放器
    private fun tryReloadPlayer(playerId: Int) {
        if (isFinishing || isDestroyed) {
            Log.d(TAG, "Activity已销毁，取消重连播放器$playerId")
            return
        }
//        try {
//            when (playerId) {
//                1 -> {
//                    player1.release()
//                    player1 = createPlayer(R.id.playerView1, streamUrl1)
//                }
//                2 -> {
//                    player2.release()
//                    player2 = createPlayer(R.id.playerView2, streamUrl2)
//                }
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "重载播放器失败", e)
//        }
    }

    // 定时器，判断连接状态
    private fun setConnectState() {
        val timer = Timer();
        val handler = Handler(Looper.getMainLooper())
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