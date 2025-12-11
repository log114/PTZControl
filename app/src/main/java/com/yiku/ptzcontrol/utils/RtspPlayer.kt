package com.yiku.ptzcontrol.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ProcessBuilder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * RTSP播放器类
 * 封装了RTSP流播放功能，支持H.264和H.265编码
 */
class RtspPlayer(
    private var url: String,
    private val surfaceView: SurfaceView,
    private val eventListener: RtspPlayerEventListener? = null
) : SurfaceHolder.Callback {

    companion object {
        private const val TAG = "RtspPlayer"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_FRAME_RATE = 30
    }

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

    // 播放状态变量
    private var isPlaying = false
    private var isStreaming = false
    private var isProcessing = false
    private var surfaceReady = false
    private var startTime = 0L

    // MediaCodec相关
    private var mediaCodec: MediaCodec? = null
    private var decoderThread: Thread? = null
    private var inputQueue = LinkedBlockingQueue<ByteArray>()

    // FFmpeg进程相关
    private var ffmpegProcess: Process? = null
    private var inputStreamThread: Thread? = null

    // 统计数据
    private var frameCount = 0
    private var totalBytesReceived = 0L

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoAspectRatio = 0f
    private var currentMimeType: String? = null
    private var isH265 = false
    private var isRestarting = false
    private var restartAttempts = 0
    private val MAX_RESTART_ATTEMPTS = 3

    init {
        // 设置Surface回调
        surfaceView.holder.addCallback(this)
    }

    /**
     * 开始播放RTSP流
     * @param rtspUrl RTSP流地址
     */
    fun startPlayback(rtspUrl: String) {
        if (isPlaying && !isRestarting) {
            eventListener?.onLogMessage("正在播放中，请先停止当前播放")
            return
        }

        if (!surfaceReady) {
            eventListener?.onLogMessage("Surface尚未准备就绪")
            return
        }

        if (rtspUrl.isEmpty()) {
            eventListener?.onError("RTSP流地址不能为空")
            return
        }

        eventListener?.onLogMessage("开始播放: $rtspUrl")
        startTime = System.currentTimeMillis()
        isPlaying = true
        frameCount = 0
        totalBytesReceived = 0L
        restartAttempts = 0

        // 在后台线程中启动播放
        thread {
            try {
                // 探测视频编码
                val streamInfo = detectVideoStreamInfo(rtspUrl)
                val newIsH265 = streamInfo.codecName.contains("hevc", true) ||
                        streamInfo.codecName.contains("h265", true)

                eventListener?.onLogMessage("检测到视频编码: ${streamInfo.codecName}")
                eventListener?.onLogMessage("视频分辨率: ${streamInfo.width}x${streamInfo.height}")
                eventListener?.onLogMessage("帧率: ${streamInfo.frameRate}")
                eventListener?.onLogMessage("是否为H.265: $newIsH265")

                if (streamInfo.codecName.isEmpty()) {
                    eventListener?.onError("无法探测视频流信息")
                    isPlaying = false
                    return@thread
                }

                // 更新编码信息
                isH265 = newIsH265
                videoWidth = streamInfo.width
                videoHeight = streamInfo.height
                currentMimeType = if (isH265) "video/hevc" else "video/avc"

                // 停止之前的播放进程
                stopFFmpegProcess()
                stopMediaCodec()

                // 初始化MediaCodec
                if (!initMediaCodec(currentMimeType!!, videoWidth, videoHeight)) {
                    eventListener?.onError("MediaCodec初始化失败")
                    isPlaying = false
                    return@thread
                }

                // 通知视频尺寸变化
                eventListener?.onVideoSizeChanged(videoWidth, videoHeight)

                // 构建并执行FFmpeg命令
                val command = buildFFmpegCommand(rtspUrl, isH265)
                eventListener?.onLogMessage("执行命令: ${command.joinToString(" ")}")

                startFFmpegProcess(command, currentMimeType!!)

                // 如果是重启，重置状态
                if (isRestarting) {
                    isRestarting = false
                }

                eventListener?.onPlaying()

            } catch (e: Exception) {
                e.printStackTrace()
                eventListener?.onError("启动播放失败: ${e.message}")
                isPlaying = false
                stopFFmpegProcess()
                stopMediaCodec()
            }
        }
    }

    /**
     * 停止播放
     */
    fun stopPlayback() {
        if (isPlaying && !isRestarting) {
            isPlaying = false
            isStreaming = false
            isProcessing = false

            stopFFmpegProcess()
            stopMediaCodec()

            val playbackTime = System.currentTimeMillis() - startTime
            eventListener?.onLogMessage("播放已停止，总共播放 ${playbackTime}ms")
            eventListener?.onLogMessage("总共接收帧数: $frameCount, 总数据量: ${totalBytesReceived / 1024}KB")
            eventListener?.onStopped()
        }
    }

    /**
     * 暂停播放
     */
    fun pausePlayback() {
        isStreaming = false
        isProcessing = false
        eventListener?.onLogMessage("播放已暂停")
    }

    /**
     * 释放资源
     */
    fun release() {
        stopPlayback()
        // 移除Surface回调
        surfaceView.holder.removeCallback(this)
        eventListener?.onLogMessage("RTSP播放器资源已释放")
    }

    /**
     * 优化的流切换方法，避免完全重新初始化
     */
    fun switchStreamOptimized(newUrl: String, forceReinit: Boolean = false): Boolean {
        Log.d(TAG, "开始优化切换视频流: $url -> $newUrl")

        return try {
            isRestarting = true
            isStreaming = false
            isProcessing = false

            // 停止当前流处理但不释放MediaCodec
            stopFFmpegProcess()
            inputQueue.clear()

            // 如果强制重新初始化或编码格式可能变化，则完全重启
            if (forceReinit || shouldReinitializeCodec(newUrl)) {
                Log.d(TAG, "需要重新初始化MediaCodec")
                stopMediaCodec()
                url = newUrl
                startPlayback(newUrl)
            } else {
                // 快速路径：只重启FFmpeg进程，复用MediaCodec
                Log.d(TAG, "快速切换：复用MediaCodec")
                url = newUrl
                startFFmpegProcess(buildFFmpegCommand(newUrl, isH265), currentMimeType!!)
                isProcessing = true
                startDecoderThread()
            }

            isRestarting = false
            true
        } catch (e: Exception) {
            eventListener?.onError("优化切换视频流失败: ${e.message}")
            isRestarting = false
            false
        }
    }

    /**
     * 检查是否需要重新初始化解码器
     */
    private fun shouldReinitializeCodec(newUrl: String): Boolean {
        // 如果已知流参数相同，可以跳过重新初始化
        // 这里可以添加更智能的检测逻辑，比如缓存流信息进行比较
        return false // 临时返回false，假设流参数相同
    }

    /**
     * 获取播放状态
     */
    fun isPlaying(): Boolean = isPlaying

    // SurfaceHolder.Callback实现
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        eventListener?.onLogMessage("Surface已创建")
        if (isPlaying) {
            // 如果之前正在播放，重新开始
            thread {
                Thread.sleep(100) // 给Surface一点时间
                startPlayback(url)
            }
        } else {
            startPlayback(url)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        eventListener?.onLogMessage("Surface尺寸变化: ${width}x$height")
        eventListener?.onVideoSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        eventListener?.onLogMessage("Surface被销毁")
        stopPlayback()
    }

    // 私有方法实现
    private fun buildFFmpegCommand(rtspUrl: String, isH265: Boolean): Array<String> {
        val mimeType = if (isH265) "hevc" else "h264"
        val bsfFilter = if (isH265) "hevc_mp4toannexb" else "h264_mp4toannexb"

        return arrayOf(
            "ffmpeg",
            "-rtsp_transport", "tcp",
            "-fflags", "+genpts",
            "-flags", "low_delay",
            "-analyzeduration", "1000000",
            "-probesize", "1000000",
            "-i", rtspUrl,
            "-c:v", "copy",
            "-f", mimeType,
            "-bsf:v", bsfFilter,
            "-an",
            "-max_delay", "100000",
            "-"
        )
    }

    private fun startFFmpegProcess(command: Array<String>, mimeType: String) {
        try {
            val processBuilder = ProcessBuilder(*command)
            ffmpegProcess = processBuilder.start()

            isStreaming = true
            inputStreamThread = thread {
                processVideoStream(ffmpegProcess!!.inputStream, mimeType)
            }

            // 错误流处理线程
            thread {
                val errorStream = ffmpegProcess!!.errorStream
                val reader = BufferedReader(InputStreamReader(errorStream))
                try {
                    var line: String?
                    while (isStreaming) {
                        line = reader.readLine()
                        if (line != null) {
                            eventListener?.onLogMessage("FFmpeg: $line")
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isStreaming) {
                        eventListener?.onLogMessage("FFmpeg错误流读取异常: ${e.message}")
                    }
                } finally {
                    reader.close()
                }
            }

        } catch (e: Exception) {
            eventListener?.onError("启动FFmpeg进程失败: ${e.message}")
            isStreaming = false

            // 如果启动失败，尝试重启
            if (restartAttempts < MAX_RESTART_ATTEMPTS) {
                restartAttempts++
                eventListener?.onLogMessage("第${restartAttempts}次尝试重启...")
                Thread.sleep(1000)
                startFFmpegProcess(command, mimeType)
            }
        }
    }

    private fun processVideoStream(inputStream: InputStream, mimeType: String) {
        val buffer = ByteArray(1024 * 1024) // 1MB缓冲区
        var bytesRead: Int

        try {
            while (isStreaming && isPlaying) {
                bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    eventListener?.onLogMessage("视频流结束")
                    break
                }

                if (bytesRead > 0) {
                    val videoData = buffer.copyOf(bytesRead)
                    totalBytesReceived += bytesRead

                    if (!inputQueue.offer(videoData)) {
                        eventListener?.onLogMessage("输入队列已满，丢弃帧")
                    }

                    frameCount++
                    if (frameCount % 30 == 0) {
                        val currentTime = System.currentTimeMillis()
                        val duration = currentTime - startTime
                        val fps = (frameCount * 1000) / duration
                        eventListener?.onLogMessage("已接收${frameCount}帧, FPS: $fps, 数据量: ${totalBytesReceived / 1024}KB")
                    }
                }
            }
        } catch (e: Exception) {
            eventListener?.onError("读取视频流失败: ${e.message}")
        } finally {
            inputStream.close()
            eventListener?.onLogMessage("视频流处理线程结束")
        }
    }

    private fun initMediaCodec(mimeType: String, width: Int, height: Int): Boolean {
        // 添加Surface有效性检查
        if (!surfaceView.holder.surface.isValid) {
            eventListener?.onError("Surface is not valid")
            return false
        }
        return try {
            // 保存视频原始尺寸
            videoWidth = width
            videoHeight = height
            videoAspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 0f

            // 先释放旧的解码器
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (e: Exception) {
                // 忽略释放异常
            }

            // 创建新的解码器
            mediaCodec = MediaCodec.createDecoderByType(mimeType)

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            // 关键：配置到Surface，让Android系统处理缩放和宽高比
            mediaCodec?.configure(format, surfaceView.holder.surface, null, 0)
            mediaCodec?.start()

            isProcessing = true
            startDecoderThread()

            eventListener?.onLogMessage("MediaCodec初始化成功: $mimeType, 分辨率: ${width}x${height}, 宽高比: $videoAspectRatio")
            true
        } catch (e: Exception) {
            eventListener?.onError("MediaCodec初始化失败: ${e.message}")
            false
        }
    }

    private fun startDecoderThread() {
        decoderThread?.interrupt()
        decoderThread = thread {
            try {
                while (isProcessing && isPlaying) {
                    val videoData = inputQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (videoData != null && videoData.isNotEmpty()) {
                        feedToMediaCodec(videoData)
                    }
                    processMediaCodecOutput()
                    Thread.yield()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                eventListener?.onError("解码线程异常: ${e.message}")
            }
        }
    }

    private fun feedToMediaCodec(data: ByteArray) {
        try {
            mediaCodec?.let { codec ->
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(data)

                    val presentationTimeUs = System.nanoTime() / 1000
                    codec.queueInputBuffer(inputBufferIndex, 0, data.size, presentationTimeUs, 0)

                    if (frameCount % 30 == 0) {
                        eventListener?.onLogMessage("提交数据到MediaCodec: ${data.size}字节")
                    }
                }
            }
        } catch (e: Exception) {
            eventListener?.onLogMessage("feedToMediaCodec失败: ${e.message}")
        }
    }

    private fun processMediaCodecOutput() {
        try {
            mediaCodec?.let { codec ->
                val bufferInfo = MediaCodec.BufferInfo()
                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

                while (outputBufferIndex >= 0) {
                    when (outputBufferIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            eventListener?.onLogMessage("输出格式已更改: $newFormat")
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            eventListener?.onLogMessage("输出缓冲区已更改")
                        }
                        else -> {
                            if (outputBufferIndex >= 0 && bufferInfo.size > 0) {
                                codec.releaseOutputBuffer(outputBufferIndex, true)
                                eventListener?.onFrameRendered(frameCount)
                            }
                        }
                    }
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            eventListener?.onLogMessage("processMediaCodecOutput失败: ${e.message}")
        }
    }

    private fun stopFFmpegProcess() {
        try {
            ffmpegProcess?.destroy()
            ffmpegProcess = null
        } catch (e: Exception) {
            eventListener?.onLogMessage("停止FFmpeg进程异常: ${e.message}")
        }

        inputStreamThread?.interrupt()
        inputStreamThread?.join(1000)
        inputStreamThread = null
        isStreaming = false
    }

    private fun stopMediaCodec() {
        isProcessing = false
        decoderThread?.interrupt()
        decoderThread?.join(1000)
        decoderThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) {
            eventListener?.onLogMessage("停止MediaCodec异常: ${e.message}")
        }
    }

    /**
     * 探测视频流信息（编码器+分辨率）
     */
    data class VideoStreamInfo(
        val codecName: String,
        val width: Int,
        val height: Int,
        val frameRate: Double
    )

    private fun detectVideoStreamInfo(rtspUrl: String): VideoStreamInfo {
        return try {
            // 探测视频流详细信息
            val probeCommand = "-v error -select_streams v:0 -show_entries stream=codec_name,width,height,r_frame_rate -of csv=p=0 \"$rtspUrl\""
            val session = FFprobeKit.execute(probeCommand)

            if (session.returnCode.isValueSuccess) {
                val output = session.output?.trim() ?: ""
                val parts = output.split(",")

                if (parts.size >= 4) {
                    val codecName = parts[0]
                    val width = parts[1].toIntOrNull() ?: DEFAULT_WIDTH
                    val height = parts[2].toIntOrNull() ?: DEFAULT_HEIGHT
                    val frameRate = parseFrameRate(parts[3])

                    VideoStreamInfo(codecName, width, height, frameRate)
                } else {
                    VideoStreamInfo("", DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FRAME_RATE.toDouble())
                }
            } else {
                VideoStreamInfo("", DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FRAME_RATE.toDouble())
            }
        } catch (e: Exception) {
            VideoStreamInfo("", DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FRAME_RATE.toDouble())
        }
    }

    private fun parseFrameRate(frameRateStr: String): Double {
        return try {
            if (frameRateStr.contains("/")) {
                val parts = frameRateStr.split("/")
                if (parts.size == 2) {
                    parts[0].toDouble() / parts[1].toDouble()
                } else {
                    DEFAULT_FRAME_RATE.toDouble()
                }
            } else {
                frameRateStr.toDoubleOrNull() ?: DEFAULT_FRAME_RATE.toDouble()
            }
        } catch (e: Exception) {
            DEFAULT_FRAME_RATE.toDouble()
        }
    }
}