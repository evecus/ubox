package com.me.ubox.player

import android.view.Surface
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.Locale

/**
 * IjkPlayer (ijkplayer / ffmpeg) 内核.
 *
 * 与 ExoPlayer 的差异 (上层无需关心, 这里抹平):
 *   - 一个 IjkMediaPlayer 实例只能 setDataSource 一次 → 换源/回退都重建实例;
 *   - 没有 isPlaying 变更回调 → play/pause/stop/completion 处手动通知;
 *   - 缓冲状态来自 onInfo 的 MEDIA_INFO_BUFFERING_START/END;
 *   - 直播与点播用不同参数 (直播关缓冲降延迟, 点播保留缓冲以便 seek).
 *
 * 解码策略 (设置页 → 解码方式):
 *   ijk_hard / ijk_hard_h264 / ijk_hard 由 mediacodec 系列 option 控制,
 *   ijk_auto 在硬解失败 (onError) 时把 softFallback 置 true 并重建为纯软解实例.
 */
class IjkEngine : PlayerEngine {

    private var player: IjkMediaPlayer? = null
    private var request: PlayRequest? = null
    private var surface: Surface? = null

    /** 最近一次 open 是否要求播放 (ijk 需等 prepared 后才能 start). */
    private var wantPlay = false

    private var speed = 1f
    private var startMs = 0L
    private var started = false

    /** ijk_auto: 硬解失败后置 true, 重建实例时改为纯软解. */
    private var softFallback = false

    private var released = false
    private var durationMs = 0L

    override var listener: PlayerListener? = null

    override val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    override val currentPosition: Long
        get() = try {
            (player?.currentPosition ?: 0L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }

    override val duration: Long
        get() {
            // 直播/未就绪: ijk 返回 0 或负数 → 上层按"未知时长"处理.
            if (!started) return durationMs.coerceAtLeast(0L)
            val d = try {
                player?.duration ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (d > 0) durationMs = d
            return durationMs.coerceAtLeast(0L)
        }

    override val bufferedPosition: Long
        get() {
            // ijk 没有"已缓冲到哪"的位置, 用 当前位置 + 视频缓存时长 近似 (进度条缓冲量).
            val cached = try {
                player?.videoCachedDuration ?: 0L
            } catch (_: Exception) {
                0L
            }
            return (currentPosition + cached.coerceAtLeast(0L))
        }

    override fun setSurface(surface: Surface?) {
        val prev = this.surface
        this.surface = surface
        val p = player ?: return
        // 起播后才拿到表面 (直播页常见: 先播放再挂载 SurfaceView): ijk 的视频输出要
        // 重建才能绑到新的 ANativeWindow, 这里按当前位置重新拉一次流, 保证出画面.
        if (surface != null && prev == null && started) {
            startMs = currentPosition
            createAndPrepare()
            if (wantPlay) play()
            return
        }
        try {
            p.setSurface(surface)
        } catch (_: Exception) {
        }
    }

    override fun open(request: PlayRequest) {
        this.request = request
        this.startMs = request.startMs
        this.durationMs = 0L
        // 换媒体项: 自动模式重新从硬解开始.
        this.softFallback = false
        createAndPrepare()
    }

    override fun play() {
        wantPlay = true
        val p = player
        if (p != null && started) {
            try {
                p.start()
            } catch (_: Exception) {
            }
            listener?.onPlayingChanged(true)
        }
    }

    override fun pause() {
        wantPlay = false
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (_: Exception) {
        }
        listener?.onPlayingChanged(false)
    }

    override fun stop() {
        wantPlay = false
        started = false
        releasePlayer()
        listener?.onPlayingChanged(false)
        listener?.onBuffering(false)
    }

    override fun seekTo(ms: Long) {
        startMs = ms
        val p = player
        if (p == null || !started) return
        try {
            p.seekTo(ms.coerceAtLeast(0L))
        } catch (_: Exception) {
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        try {
            player?.setSpeed(speed)
        } catch (_: Exception) {
        }
    }

    override fun release() {
        released = true
        releasePlayer()
    }

    override fun describe(): String = "${DecoderPrefs.kernelName()} · ${describeMode()}"

    private fun describeMode(): String {
        if (softFallback) return "自动 (已切换软解)"
        return when (DecoderPrefs.current) {
            DecoderPrefs.IJK_HARD -> "硬解 (无回退)"
            DecoderPrefs.IJK_HARD_H264 -> "硬解 (H.265 软解)"
            DecoderPrefs.IJK_SOFT -> "软解 (无回退)"
            else -> "自动 (硬解)"
        }
    }

    // ================================================================ 实例

    private fun createAndPrepare() {
        if (released) return
        val req = request ?: return
        releasePlayer()
        if (!ensureNative()) {
            listener?.onError(
                PlayerEngines.ERROR_NO_DECODER,
                "IjkPlayer 初始化失败 (设备可能不支持该 ABI)\n解码方式: ${describe()}",
            )
            return
        }
        val p = try {
            IjkMediaPlayer()
        } catch (e: Throwable) {
            listener?.onError(-1, "IjkPlayer 创建失败: ${e.message ?: ""}")
            return
        }
        applyOptions(p, req)
        p.setOnPreparedListener { onPrepared(p) }
        p.setOnInfoListener { _, what, extra -> onInfo(what, extra) }
        p.setOnErrorListener { _, what, extra -> onError(what, extra) }
        p.setOnCompletionListener { onCompletion() }
        p.setOnVideoSizeChangedListener { _, width, height, sarNum, sarDen -> onSize(width, height, sarNum, sarDen) }
        p.setOnBufferingUpdateListener { _, _ -> }
        try {
            if (req.headers.isEmpty()) {
                p.setDataSource(req.url)
            } else {
                p.setDataSource(req.url, req.headers)
            }
            p.setSurface(surface)
            p.prepareAsync()
        } catch (e: Exception) {
            listener?.onError(-1, "IjkPlayer 打开失败: ${e.message ?: ""}")
            try {
                p.release()
            } catch (_: Exception) {
            }
            return
        }
        player = p
    }

    private fun onPrepared(p: IjkMediaPlayer) {
        started = true
        try {
            p.setSpeed(speed)
        } catch (_: Exception) {
        }
        if (startMs > 0) {
            try {
                p.seekTo(startMs)
            } catch (_: Exception) {
            }
        }
        try {
            durationMs = p.duration.coerceAtLeast(0L)
        } catch (_: Exception) {
        }
        listener?.onBuffering(false)
        listener?.onReady()
        try {
            val w = p.videoWidth
            val h = p.videoHeight
            if (w > 0 && h > 0) {
                val num = p.videoSarNum
                val den = p.videoSarDen
                listener?.onVideoSize(w, h, if (den > 0) num.toFloat() / den else 1f)
            }
        } catch (_: Exception) {
        }
        if (wantPlay) {
            try {
                p.start()
            } catch (_: Exception) {
            }
            listener?.onPlayingChanged(true)
        }
    }

    private fun onInfo(what: Int, extra: Int): Boolean {
        when (what) {
            IMediaPlayer.MEDIA_INFO_BUFFERING_START -> listener?.onBuffering(true)
            IMediaPlayer.MEDIA_INFO_BUFFERING_END -> listener?.onBuffering(false)
            IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                listener?.onBuffering(false)
                if (started) listener?.onPlayingChanged(true)
            }
        }
        return false
    }

    private fun onError(what: Int, extra: Int): Boolean {
        // ijk_auto: 硬解失败 → 重建为纯软解重试一次.
        if (DecoderPrefs.current == DecoderPrefs.IJK_AUTO && !softFallback && !released) {
            softFallback = true
            val keep = request
            if (keep != null) {
                listener?.onNotice("硬解失败, 已切换软解")
                createAndPrepare()
                if (wantPlay) play()
                return true
            }
        }
        listener?.onBuffering(false)
        listener?.onError(
            what,
            "播放失败 (IjkPlayer)\n" +
                "错误: ${ijkErrorName(what)} ($what/$extra)\n" +
                "地址类型: ${request?.mimeType ?: "自动识别"}\n" +
                "解码方式: ${describe()}",
        )
        return true
    }

    private fun onCompletion() {
        started = false
        listener?.onPlayingChanged(false)
        listener?.onEnded()
    }

    private fun onSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
        if (width <= 0 || height <= 0) return
        listener?.onVideoSize(width, height, if (sarDen > 0) sarNum.toFloat() / sarDen else 1f)
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        started = false
        try {
            p.setOnPreparedListener(null)
            p.setOnErrorListener(null)
            p.setOnInfoListener(null)
            p.setOnCompletionListener(null)
            p.setOnVideoSizeChangedListener(null)
            p.setOnBufferingUpdateListener(null)
        } catch (_: Exception) {
        }
        try {
            if (p.isPlaying) p.stop()
        } catch (_: Exception) {
        }
        try {
            p.release()
        } catch (_: Exception) {
        }
    }

    // ================================================================ 参数

    private fun applyOptions(p: IjkMediaPlayer, req: PlayRequest) {
        val mode = DecoderPrefs.current
        val hard = when {
            softFallback -> false
            mode == DecoderPrefs.IJK_SOFT -> false
            mode == DecoderPrefs.IJK_HARD || mode == DecoderPrefs.IJK_HARD_H264 -> true
            else -> true // ijk_auto 首次硬解
        }
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (hard) 1L else 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        // H.265 硬解: ijk_hard 开启, ijk_hard_h264 关闭 (设备 HEVC 硬解花屏时用后者).
        val hevc = hard && mode != DecoderPrefs.IJK_HARD_H264
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", if (hevc) 1L else 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0L)
        // 音频: 保持软解 + 关闭 opensles (部分设备 opensles 有爆音/延迟问题).
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)

        if (req.live) {
            // 直播: 关缓冲降延迟, 断线自动重连.
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100_000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 32 * 1024L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        } else {
            // 点播: 保留缓冲 (保证拖动/弱网不卡), 探测时长放宽以正确识别格式.
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1_000_000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 1024L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        }
        // 跳过环路滤波: 高清源软解时的性能兜底 (48 = 全部跳过, 与常见 TV 播放器一致).
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        // Range 请求探测: 部分源站不支持 Range, 关掉避免 416/卡住.
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "file,http,https,crypto,tcp,tls,rtmp,rtsp,httpproxy")
    }

    private fun ijkErrorName(what: Int): String = when (what) {
        IMediaPlayer.MEDIA_ERROR_UNKNOWN -> "未知错误"
        IMediaPlayer.MEDIA_ERROR_SERVER_DIED -> "播放服务终止"
        -10000 -> "打开/解析失败 (源不可达或格式不支持)"
        -10001 -> "解码失败"
        -10002 -> "网络错误"
        -1004 -> "I/O 错误"
        else -> String.format(Locale.US, "错误 %d", what)
    }

    // ================================================================ native 库

    private companion object {

        @Volatile
        private var nativeReady = false

        @Volatile
        private var nativeError = ""

        /** 加载 ijk 的 so (进程内一次). 失败时返回 false, 由调用方转成错误提示. */
        @Synchronized
        fun ensureNative(): Boolean {
            if (nativeReady) return true
            if (nativeError.isNotEmpty()) return false
            return try {
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_profileBegin("libijkplayer.so")
                nativeReady = true
                true
            } catch (e: Throwable) {
                nativeError = e.message ?: e.javaClass.simpleName
                false
            }
        }
    }
}
