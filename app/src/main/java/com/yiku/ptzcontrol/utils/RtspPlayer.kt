package com.yiku.ptzcontrol.utils

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView

@UnstableApi
object RtspPlayer {
    interface PlayerErrorListener {
        fun onPlayerError(playerId: Int, exception: Exception)
    }

    private val TAG = "RtspPlayer"
    private var player: ExoPlayer? = null

    fun createPlayer(
        context: Context,
        playerView: PlayerView,
        url: String,
        playerId: Int, // 标识播放器 (1-小窗, 2-全屏)
        errorListener: PlayerErrorListener? = null // 错误回调
    ): ExoPlayer {
        Log.i(TAG, "视频地址：${url}")
        // 1. 创建ExoPlayer实例，并配置为低延迟模式
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true) // 开启解码器后备支持
            .setMediaCodecSelector(MediaCodecSelector.DEFAULT) // 使用默认的，会优先硬件解码

        val playerBuilder = ExoPlayer.Builder(context, renderersFactory)
            .setUseLazyPreparation(true) // 使用懒加载准备
            .setTrackSelector(DefaultTrackSelector(context).apply {
                // 设置轨道选择参数：优先考虑低延迟和低分辨率
                parameters = buildUponParameters()
                    .setPreferredTextLanguage("zh")
                    .setPreferredAudioLanguage("zh")
//                    .setForceLowestBitrate(true) // 强制选择最低码率（码率低通常延迟低）
                    .setRendererDisabled(C.TRACK_TYPE_AUDIO, true) // 禁用音频轨道，减少处理环节
                    .setMaxVideoSize(1280, 720) // 设置最大分辨率，避免高分辨率高延迟
                    .build()
            })
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        0,    // 最小缓冲时间（ms）
                        0,   // 最大缓冲时间（ms） - 通常小缓冲意味着低延迟
                        0,    // 开始播放缓冲时间
                        0    // 重新缓冲时间
                    )
                    .setPrioritizeTimeOverSizeThresholds(true) // 优先考虑时间阈值
                    .build()
            )

        player = playerBuilder.build()

        playerView.player = this.player

        // 2. 创建RTSP媒体源并强制使用TCP传输
        val rtspMediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // 强制使用TCP传输（避免UDP丢包导致的延迟）
//            .setDebugLoggingEnabled(true) // 开启RTSP协议的详细日志
            .setTimeoutMs(3000)
            .createMediaSource(MediaItem.fromUri(url))

        // 3. 设置播放器事件监听器，用于日志和错误处理
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    ExoPlayer.STATE_READY -> {
                        Log.d("PlayerState", "Player ready")
                        // 设置播放参数：1倍速（不降低播放速度）
                        player?.playbackParameters = PlaybackParameters(1.0f)
                    }
                    ExoPlayer.STATE_BUFFERING -> Log.d("PlayerState", "Player buffering")
                    ExoPlayer.STATE_ENDED -> {
                        Log.d("PlayerState", "Player ended")
                        // 流意外结束，视为错误
                        errorListener?.onPlayerError(
                            playerId,
                            Exception("Player $playerId stream ended unexpectedly")
                        )
                    }
                    ExoPlayer.STATE_IDLE -> Log.d("PlayerState", "Player idle")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlayerError", "Playback error: ${error.message}")
                errorListener?.onPlayerError(
                    playerId,
                    Exception("Player $playerId error: ${error.message}")
                )
            }
        })

        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        // 4. 开始播放
        player?.setMediaSource(rtspMediaSource)
        player?.prepare()
        player?.playWhenReady = true // 自动播放

        // 5. 设置时间同步模式：立即显示，不等待时钟
        player?.setVideoFrameMetadataListener(frameMetadataListener)
        player?.playbackParameters = PlaybackParameters(1.0f) // 正常速度播放
        player?.setSeekParameters(SeekParameters.CLOSEST_SYNC) // 定位到最近的关键帧，但一般直播不需要定位

        return player!!
    }

    private val frameMetadataListener = VideoFrameMetadataListener { presUs, releaseNs, format, mediaFormat ->
//        val currentTime = SystemClock.elapsedRealtimeNanos()
//        val frameLatencyMs = (currentTime - releaseNs) / 1_000_000

//        Log.d("FrameStats", """
//        ⏱️ Latency: ${frameLatencyMs}ms
//        🎞️ Format: ${format.sampleMimeType}
//        ⏲️ Pres: ${presUs}μs | Release: ${releaseNs}ns
//    """.trimIndent())
    }

    fun createIndependentPlayer(
        context: Context,
        url: String,
        startPosition: Long = 0,
        playWhenReady: Boolean = true,
        playerId: Int = 3, // 标识播放器 (1-小窗, 2-全屏)
        errorListener: PlayerErrorListener? = null // 错误回调
    ): ExoPlayer {
        // 复用之前的创建逻辑
        val player = createPlayer(context, PlayerView(context), url, playerId, errorListener)

        // 设置传入的状态
        player.seekTo(startPosition)
        player.playWhenReady = playWhenReady

        return player
    }

    // 判断延迟是否超过1.5s
    fun checkPlayer(player: ExoPlayer): Boolean {
        // 计算延迟 = 当前时间 - 最新帧时间戳
        val currentTime = System.currentTimeMillis()
        val latestFrameTime = player.videoSize.width.toLong() // 临时用width存储最后帧时间
        val latency = currentTime - latestFrameTime

        // 如果延迟超过1.5秒，重置播放器
        if (latency > 1500) {
            Log.w(TAG, "High latency detected: ${latency}ms")
            return true
        }
        return false
    }
}