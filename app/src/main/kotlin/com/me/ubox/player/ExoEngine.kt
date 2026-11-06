package com.me.ubox.player

import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * ExoPlayer (media3) 内核.
 *
 * 解码器过滤与"自动"模式的硬 → 软回退由 [DecoderController] 负责 (见 DecoderMode.kt),
 * 本类只做 内核事件 → [PlayerListener] 的翻译, 让上层与内核无关.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoEngine : PlayerEngine {

    private val decoder = DecoderController()
    private val exo: ExoPlayer = decoder.createPlayer()

    override var listener: PlayerListener? = null

    /** 起播位置 (open 时传入, 就绪后 seek). */
    private var startMs = 0L

    /** 当前媒体项是否已回调过 onReady (自动回退重新 prepare 时复位). */
    private var ready = false

    init {
        exo.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering(true)
                    Player.STATE_READY -> {
                        listener?.onBuffering(false)
                        if (!ready) {
                            ready = true
                            if (startMs > 0) exo.seekTo(startMs)
                            listener?.onReady()
                        }
                    }
                    Player.STATE_ENDED -> listener?.onEnded()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 自动模式: 解码器相关错误 → 逐级降级重试 (软 MediaCodec → FFmpeg), 不算失败.
                if (decoder.onPlayerError(exo, error)) {
                    ready = false
                    return
                }
                listener?.onBuffering(false)
                val cause = generateSequence<Throwable>(error) { it.cause }.last()
                listener?.onError(
                    error.errorCode,
                    "播放失败: ${error.errorCodeName}\n" +
                        "${cause.javaClass.simpleName}: ${cause.message ?: ""}\n" +
                        "解码方式: ${decoder.describe()}",
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 硬解/软解模式没有可用视频解码器: ExoPlayer 只会静音只放声音, 这里按失败处理.
                if (decoder.onTracks(exo, tracks) == DecoderController.TracksCheck.UNSUPPORTED) {
                    exo.stop()
                    listener?.onBuffering(false)
                    listener?.onError(PlayerEngines.ERROR_NO_DECODER, decoder.unsupportedMessage())
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    // pixelWidthHeightRatio 理论上恒为正, 兜底防 0 值把上层比例打成 0 (画面拉伸).
                    val ratio = videoSize.pixelWidthHeightRatio
                    // Media3 对 SurfaceView 输出由解码器转正画面, 上报的仍是编码前宽高;
                    // 旋转角从 videoFormat 取, 90/270 时上层互换宽高后比例才正确.
                    val rotation = try { exo.videoFormat?.rotationDegrees ?: 0 } catch (_: Exception) { 0 }
                    listener?.onVideoSize(
                        videoSize.width,
                        videoSize.height,
                        if (ratio > 0f) ratio else 1f,
                        if (rotation == 90 || rotation == 270) rotation else 0,
                    )
                }
            }
        })
    }

    override val isPlaying: Boolean get() = exo.isPlaying

    override val currentPosition: Long get() = exo.currentPosition.coerceAtLeast(0L)

    override val duration: Long
        get() = if (exo.duration == C.TIME_UNSET) 0L else exo.duration.coerceAtLeast(0L)

    override val bufferedPosition: Long get() = exo.bufferedPosition.coerceAtLeast(0L)

    override fun setSurface(surface: Surface?) {
        exo.setVideoSurface(surface)
    }

    override fun open(request: PlayRequest) {
        PlayerHeaders.current = request.headers
        startMs = request.startMs
        ready = false
        decoder.newItem()
        val item = MediaItem.Builder()
            .setUri(request.url)
            .apply { request.mimeType?.let { setMimeType(it) } }
            .build()
        exo.setMediaItem(item)
        exo.prepare()
    }

    override fun play() {
        exo.play()
    }

    override fun pause() {
        exo.pause()
    }

    override fun stop() {
        exo.stop()
        ready = false
    }

    override fun seekTo(ms: Long) {
        exo.seekTo(ms)
    }

    override fun setSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed)
    }

    override fun release() {
        exo.release()
    }

    override fun describe(): String = "${DecoderPrefs.kernelName()} · ${decoder.describe()}"
}
