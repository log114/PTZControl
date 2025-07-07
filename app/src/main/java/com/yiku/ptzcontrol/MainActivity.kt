package com.yiku.ptzcontrol
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView

class MainActivity : AppCompatActivity() {

    private lateinit var player1: ExoPlayer
    private lateinit var player2: ExoPlayer
    private lateinit var floatingManager: FloatingWindowManager

    // 调试标签
    private val TAG = "MainActivityDebug"

    // 记录播放器状态
    private var player1PlayWhenReady = true
    private var player2PlayWhenReady = true
    private var player1CurrentPosition = 0L
    private var player2CurrentPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        Log.d(TAG, "onCreate")

        // 初始化播放器
        player1 = createPlayer("rtsp://your_stream_url_1", R.id.playerView1)
        player2 = createPlayer("rtsp://your_stream_url_2", R.id.playerView2)

        floatingManager = FloatingWindowManager(this)

        // 启动时检查悬浮窗权限
        checkOverlayPermission()
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

        // 恢复播放器状态
        player1.playWhenReady = true
        player2.playWhenReady = true

        // 关闭悬浮窗
        floatingManager.closeFloatingWindow()
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

        if (!isChangingConfigurations) {
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
    }

    // 检查悬浮窗权限
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
    }

    // 处理权限请求结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Overlay permission granted by user")
                    Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Log.d(TAG, "Overlay permission denied by user")
                    Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val OVERLAY_PERMISSION_CODE = 1000
    }
}