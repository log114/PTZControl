package com.yiku.ptzcontrol.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import androidx.core.net.toUri
import java.util.ArrayList
import kotlin.collections.plus
import kotlin.concurrent.thread

interface PlayerCallback {
    fun onPlaying(index: Int, mediaPlayer: MediaPlayer)
    fun onError(index: Int)
}

class RtspPlayer(context: Context) {
    private val TAG = "RtspPlayer"
    private val lock = Any()
    private val _context: Context = context
    private val args = mutableListOf(
        "--network-caching=0",      // 禁用缓存（必须）
        "--clock-jitter=0",         // 降低时间戳抖动
        "--clock-synchro=0",        // 禁用时钟同步（降低延迟）
        "--live-caching=0",         // 实时模式无缓存
        "--tcp-caching=0",          // TCP流无缓存
        "--rtsp-tcp",               // 强制使用TCP（避免UDP丢包）
        "--avcodec-fast"            // 启用快速解码
    )
    var libVLC: LibVLC? = LibVLC(context, args)
    var playerCallbacks: List<PlayerCallback> = ArrayList()

    fun createPlayer(index: Int, videoLayout: VLCVideoLayout, url: String) {
        if(libVLC == null) {
            try {
                libVLC = LibVLC(_context, args)
            } catch (e: Exception) {
                Log.e(TAG, "LibVLC init failed", e)
            }
        }
        val mediaPlayer = MediaPlayer(libVLC)
        // 3. 绑定渲染视图
        mediaPlayer.attachViews(videoLayout, null, false, false)
        // 动态获取容器尺寸并更新渲染表面
        videoLayout.post {
            val width = videoLayout.width
            val height = videoLayout.height
            mediaPlayer.vlcVout.setWindowSize(width, height) // 关键：同步窗口尺寸
            Log.d(TAG, "width: ${width}, height: $height")
        }
        mediaPlayer.scale = 0f // 0=拉伸至填满（VLC_SCALE_FIT）
        mediaPlayer.aspectRatio = "" // 清空原始比例约束

        // 4. 设置媒体源（添加超时参数）
        val media = Media(libVLC, url.toUri()).apply {
            addOption(":network-caching=0")
            addOption(":rtsp-timeout=300") // 设置500ms超时
        }

        // 5. 开始播放
        mediaPlayer.media = media
        mediaPlayer.play()

        // 响应事件
        mediaPlayer.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                when (event.type) {
                    // 播放成功事件
                    MediaPlayer.Event.Playing -> {
                        for (playerCallback in playerCallbacks) {
                            playerCallback.onPlaying(index, mediaPlayer)
                        }
                    }
                    // 播放失败事件
                    MediaPlayer.Event.EncounteredError -> {
                        for (playerCallback in playerCallbacks) {
                            playerCallback.onError(index)
                        }
                        mediaPlayer.apply {
                            stop()
                            detachViews()
                            release()
                        }
                        thread {
                            Thread.sleep(1000)
                            val handler = Handler(Looper.getMainLooper())
                            handler.post {
                                createPlayer(index, videoLayout, url) // 递归重建
                            }
                        }
                    }
                    // 缓冲事件（可选）
                    MediaPlayer.Event.Buffering -> {
                        if (event.buffering == 100f) {
                            Log.d(TAG, "缓冲完成，播放流畅")
                        }
                    }
                }
            }
        })
    }

    fun registPlayerCallback(playerCallback: PlayerCallback) {
        this.playerCallbacks += playerCallback
    }

    fun release() {
        synchronized(lock) {
            if(libVLC != null) {
                libVLC!!.release()
                libVLC = null
            }
        }
    }
}