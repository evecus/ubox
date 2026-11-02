package com.me.ubox.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.me.ubox.data.Ctx
import com.me.ubox.ui.AppSnackbar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 解码方式设置 (设置页 → 播放 → 解码方式), 点播与直播共用.
 *
 * 每项 = 播放内核 + 解码策略:
 *   ExoPlayer (media3):
 *     auto      自动: 先硬解, 硬解失败 (解码器相关错误) 再自动切软解重试;
 *     hard      硬解: 视频只用硬件解码器, 无回退;
 *     soft      软解: 视频只用软件解码器, 无回退.
 *   IjkPlayer (ijkplayer, ffmpeg):
 *     ijk_auto       自动: 先 MediaCodec 硬解, 失败自动重建为软解重试;
 *     ijk_hard       硬解: MediaCodec 硬解 (含 H.265), 无回退;
 *     ijk_hard_h264  硬解(不含 H.265): H.264 走 MediaCodec, H.265 走软解
 *                    (部分盒子 HEVC 硬解花屏/黑屏时用它);
 *     ijk_soft       软解: 纯 ffmpeg 软件解码, 兼容性最好, 高清源占 CPU.
 *
 * ExoPlayer 三项只约束 **视频** 解码器. 音频解码器在 Android 上几乎都是软件实现
 * (c2.android.*), 若音频也按"硬解"过滤会导致没有声音, 所以音频始终走系统默认选择.
 */
object DecoderPrefs {

    // ---- ExoPlayer ----
    const val AUTO = "auto"
    const val HARD = "hard"
    const val SOFT = "soft"

    // ---- IjkPlayer ----
    const val IJK_AUTO = "ijk_auto"
    const val IJK_HARD = "ijk_hard"
    /** 硬解但排除 H.265 (HEVC 走软解). */
    const val IJK_HARD_H264 = "ijk_hard_h264"
    const val IJK_SOFT = "ijk_soft"

    private const val KEY = "decoder_mode"

    /** 设置页选项顺序 (先 ExoPlayer 后 IjkPlayer, 每组 自动/硬解/软解). */
    private val ORDER = listOf(AUTO, HARD, SOFT, IJK_AUTO, IJK_HARD, IJK_HARD_H264, IJK_SOFT)

    private val _mode by lazy { MutableStateFlow(normalize(Ctx.prefs.getString(KEY, AUTO))) }

    /** 当前解码方式 (设置页观察). */
    val mode: StateFlow<String> get() = _mode.asStateFlow()

    val current: String get() = _mode.value

    /** 全部可选项 (设置页单选列表). */
    fun options(): List<String> = ORDER

    /** 该方式是否走 IjkPlayer 内核. */
    fun isIjk(value: String = current): Boolean = value.startsWith("ijk_")

    /** 内核名 (错误提示/关于页展示). */
    fun kernelName(value: String = current): String = if (isIjk(value)) "IjkPlayer" else "ExoPlayer"

    private fun normalize(value: String?): String = if (value in ORDER) value!! else AUTO

    fun setMode(value: String) {
        val v = normalize(value)
        _mode.value = v
        Ctx.prefs.edit().putString(KEY, v).apply()
    }

    fun label(value: String): String = when (value) {
        HARD -> "硬解 (ExoPlayer)"
        SOFT -> "软解 (ExoPlayer)"
        IJK_AUTO -> "IJK 自动 (硬解→软解)"
        IJK_HARD -> "IJK 硬解"
        IJK_HARD_H264 -> "IJK 硬解 (不含 H.265)"
        IJK_SOFT -> "IJK 软解"
        else -> "自动 (ExoPlayer)"
    }

    /** 一句话说明 (设置页列表副标题, 保持单行可读). */
    fun description(value: String): String = when (value) {
        HARD -> "ExoPlayer 只用硬解, 无回退"
        SOFT -> "ExoPlayer 只用软解, 无回退"
        IJK_AUTO -> "IJK 先硬解, 失败自动切软解"
        IJK_HARD -> "IJK 硬解 (含 H.265), 无回退"
        IJK_HARD_H264 -> "IJK 硬解 H.264, H.265 走软解"
        IJK_SOFT -> "IJK 纯软解, 兼容性最好"
        else -> "ExoPlayer 先硬解, 失败自动切软解"
    }

    /** 详细说明 (设置页主项副标题). */
    fun detail(value: String): String = when (value) {
        HARD -> "只用硬件解码器, 失败即报错"
        SOFT -> "只用软件解码器, 失败即报错"
        IJK_AUTO -> "MediaCodec 硬解, 失败时重建为纯软解实例重试"
        IJK_HARD -> "只用 MediaCodec 硬解 (含 H.265), 失败即报错"
        IJK_HARD_H264 -> "H.264 走 MediaCodec, H.265 走软解 (设备 HEVC 硬解花屏时用)"
        IJK_SOFT -> "只用 ffmpeg 软件解码, 兼容性最好, 高清源更耗 CPU"
        else -> "先硬件解码, 解码失败自动切换软解重试"
    }
}

/**
 * 每个 ExoPlayer 一个: 负责按 [DecoderPrefs] 过滤视频解码器, 以及"自动"模式的硬 -> 软切换.
 *
 * 切换靠一个会随状态变化的 [MediaCodecSelector]: 渲染器每次初始化解码器 / 评估轨道支持度时
 * 都会重新向它查询, 所以只要改 [softFallback] 再 stop() + prepare() 重新准备即可,
 * 不需要重建播放器 (表面/监听/弹幕都保持不变).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DecoderController {

    /** 自动模式下: 当前媒体项硬解失败后置 true, 改用软解 (换新媒体项时复位). */
    @Volatile
    private var softFallback = false

    private val selector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val all: List<MediaCodecInfo> =
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (!MimeTypes.isVideo(mimeType)) {
            all
        } else if (useSoftware()) {
            all.filter { it.softwareOnly }
        } else {
            all.filter { it.hardwareAccelerated && !it.softwareOnly }
        }
    }

    private fun useSoftware(): Boolean = when (DecoderPrefs.current) {
        DecoderPrefs.SOFT -> true
        DecoderPrefs.HARD -> false
        else -> softFallback
    }

    /** 创建按解码方式配置好的 ExoPlayer (点播/直播共用). */
    fun createPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(Ctx.app)
            .setMediaCodecSelector(selector)
            // 仅在"已过滤后的同类解码器列表"里依次尝试 (例如多个硬件解码器), 不会跨到另一类.
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(Ctx.app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PlayerDataSourceFactory))
            .build()
    }

    /** 即将播放新的媒体项 (换集 / 换台 / 换源 / 重试): 自动模式重新从硬解开始. */
    fun newItem() {
        softFallback = false
    }

    /** 给错误提示用: 当前实际使用的解码方式. */
    fun describe(): String = when (DecoderPrefs.current) {
        DecoderPrefs.HARD -> "硬解 (无回退)"
        DecoderPrefs.SOFT -> "软解 (无回退)"
        else -> if (softFallback) "自动 (已切换软解)" else "自动 (硬解)"
    }

    private fun isDecoderError(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

    /** 自动模式且尚未回退过: 切到软解并从当前位置重新准备. */
    private fun switchToSoftAndRetry(player: ExoPlayer): Boolean {
        if (DecoderPrefs.current != DecoderPrefs.AUTO || softFallback) return false
        softFallback = true
        val live = player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET
        val pos = player.currentPosition
        player.stop()
        if (!live) player.seekTo(pos) else player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
        AppSnackbar.show("硬解失败, 已切换软解")
        return true
    }

    /**
     * 播放器报错时调用. 返回 true = 解码器相关错误且已在自动模式下切软解重试,
     * 调用方不要再当作失败处理; false = 按原有流程处理错误.
     */
    fun onPlayerError(player: ExoPlayer, error: PlaybackException): Boolean {
        if (!isDecoderError(error)) return false
        return switchToSoftAndRetry(player)
    }

    /** [onTracks] 的结果. */
    enum class TracksCheck { OK, FALLBACK_STARTED, UNSUPPORTED }

    /**
     * 轨道变化时调用. 过滤后若没有可用的视频解码器 (例如"硬解"模式但设备没有该格式的硬解器),
     * ExoPlayer 不会报错, 而是悄悄丢掉视频轨只播声音 —— 这里把它识别为失败:
     * 自动模式切软解重试 (FALLBACK_STARTED); 硬解/软解模式返回 UNSUPPORTED 让调用方报错.
     */
    fun onTracks(player: ExoPlayer, tracks: Tracks): TracksCheck {
        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (!hasVideo || tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)) return TracksCheck.OK
        return if (switchToSoftAndRetry(player)) TracksCheck.FALLBACK_STARTED else TracksCheck.UNSUPPORTED
    }

    fun unsupportedMessage(): String = "当前设备没有可用于该视频的解码器\n解码方式: ${describe()}"
}
