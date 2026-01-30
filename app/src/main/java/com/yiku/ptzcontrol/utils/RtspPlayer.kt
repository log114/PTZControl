package com.yiku.ptzcontrol.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.LinearLayout
import com.jxj.ffmpegrtsp.lib.FFmpegCallbacks
import com.jxj.ffmpegrtsp.lib.FFmpegRTSPLibrary
import com.jxj.ffmpegrtsp.lib.VideoInfo
import com.yiku.ptzcontrol.modules.PlayerSurfaceView
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/**
 * RTSP播放器类
 * 封装了RTSP流播放功能，支持H.264和H.265编码
 */
class RtspPlayer(
    private val context: Context,
    private val eventListener: RtspPlayerEventListener? = null
) {

    /**
     * RTSP播放器事件监听接口
     */
    interface RtspPlayerEventListener {
        fun onPlaying()
        fun onStopped()
        fun onError(errorMessage: String)
        fun onLogMessage(message: String)
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onFrameRendered(frameCount: Int)
    }

    private val operationLock = java.util.concurrent.locks.ReentrantLock()
    // 播放状态变量
    private var isPlaying = false
    private val TAG = "RtspPlayer"

    // 添加获取方法
    private val streamItems = mutableListOf<StreamItem>()

    init { }

    private fun playStream(streamItem: StreamItem) {
        if (!streamItem.isPlaying) {
            FFmpegRTSPLibrary.startPlayAsync(streamItem.streamId, object : FFmpegCallbacks.PlaybackStartCallback {
                override fun onPlaybackStarted(streamId: Int, videoInfo: VideoInfo?) {
                    streamItem.isPlaying = true
                }

                override fun onPlaybackError(streamId: Int, errorCode: Int, errorMessage: String) {
                    streamItem.isPlaying = false
                }
            })
        } else {
            stopStream(streamItem)
        }
    }

    private fun stopStream(streamItem: StreamItem) {
        if (streamItem.isPlaying) {
            FFmpegRTSPLibrary.stopPlayAsync(streamItem.streamId, object : FFmpegCallbacks.PlaybackStopCallback {
                override fun onPlaybackStopped(streamId: Int) {
                    streamItem.isPlaying = false
                }

                override fun onPlaybackError(streamId: Int, errorCode: Int, errorMessage: String) {
                    streamItem.isPlaying = false
                }
            })
        }
    }

    fun addStream(rtspUrl: String, playerBox: LinearLayout): Boolean {
        return operationLock.withLock {
            try {
                val streamId = FFmpegRTSPLibrary.createStream(rtspUrl)
                if (streamId >= 0) {
                    val streamItem = StreamItem(streamId)
                    streamItems.add(streamItem)
                    addStreamView(streamItem, playerBox)
                    true
                } else {
                    eventListener?.onError("创建流失败")
                    false
                }
            } catch (e: Exception) {
                eventListener?.onError("添加流异常: ${e.message}")
                false
            }
        }
    }

    private fun addStreamView(streamItem: StreamItem, playerBox: LinearLayout) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            // 创建MySurfaceView实例
            val surfaceView = PlayerSurfaceView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            streamItem.setView(surfaceView)
            playerBox.addView(surfaceView)
        }
    }

    private fun removeStream(streamItem: StreamItem) {
        if (streamItem.isPlaying) {
            stopStream(streamItem)
        }
        FFmpegRTSPLibrary.destroyStream(streamItem.streamId)
        streamItems.remove(streamItem)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val streamViewParent = streamItem.surfaceView?.parent
            if (streamViewParent is ViewGroup) {
                streamViewParent.removeView(streamItem.surfaceView)
            }
        }
    }

    fun playAllStreams() {
        Log.d(TAG, "开始播放")
        streamItems.forEach { streamItem ->
            if (!streamItem.isPlaying) {
                playStream(streamItem)
            }
        }
        Log.d(TAG, "播放成功")
        thread {
            var waitCount = 0
            while (true) {
                Thread.sleep(1000)
                waitCount ++
                var result = true
                streamItems.forEach { streamItem ->
                    if (!streamItem.isPlaying) {
                        result = false
                    }
                }
                if(result) {
                    eventListener?.onPlaying()
                    break
                }
                else if(waitCount >= 5) {
                    eventListener?.onError("播放失败")
                    break
                }
            }
        }
    }

    fun stopAllStreams() {
        streamItems.forEach { streamItem ->
            if (streamItem.isPlaying) {
                stopStream(streamItem)
            }
        }
        eventListener?.onStopped()
    }

    fun clearAllStreams() {
        streamItems.toList().forEach { streamItem ->
            removeStream(streamItem)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        operationLock.withLock {
            clearAllStreams()
        }
        eventListener?.onLogMessage("RTSP播放器资源已释放")
    }

    /**
     * 获取播放状态
     */
    fun isPlaying(): Boolean = isPlaying

    // 流项目内部类
    private inner class StreamItem(
        val streamId: Int
    ) : SurfaceHolder.Callback {

        var isPlaying = false
        var surfaceView: SurfaceView? = null
        private var surfaceHolder: SurfaceHolder? = null

        fun setView(view: SurfaceView) {
            surfaceView = view
            surfaceHolder = surfaceView?.holder
            surfaceHolder?.addCallback(this)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            FFmpegRTSPLibrary.setSurface(streamId, holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            FFmpegRTSPLibrary.onSurfaceDestroyed(streamId)
        }
    }
}

// 扩展函数简化锁操作
inline fun <T> java.util.concurrent.locks.Lock.withLock(operation: () -> T): T {
    lock()
    try {
        return operation()
    } finally {
        unlock()
    }
}