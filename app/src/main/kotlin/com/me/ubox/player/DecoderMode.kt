package com.me.ubox.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.me.ubox.data.Ctx
import com.me.ubox.ui.AppSnackbar
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode as NextDecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 解码方式设置 (设置页 → 播放 → 解码方式), 点播与直播共用.
 *
 * 每项 = 视频解码策略 (内核固定为 ExoPlayer + NextLib 扩展渲染器):
 *   auto  自动: 硬解 MediaCodec → 软解 MediaCodec (c2) → FFmpeg 逐级兜底,
 *         运行时解码失败再强制降一级 (软 MediaCodec → FFmpeg);
 *   hard  硬解: 只用硬件 MediaCodec, 无回退;
 *   soft  软解: 只用内置 FFmpeg/dav1d 软解渲染器, 无回退.
 *
 * 三项只约束 **视频** 解码器. 音频始终走 AUTO (硬 → 软 → FFmpeg 自动兜底),
 * 系统解码器不支持的 AC3/EAC3/DTS/TrueHD 等音频由 FFmpeg 渲染器接管.
 */
object DecoderPrefs {

    const val AUTO = "auto"
    const val HARD = "hard"
    const val SOFT = "soft"

    private const val KEY = "decoder_mode"

    /** 设置页选项顺序 (自动 / 硬解 / 软解). */
    private val ORDER = listOf(AUTO, HARD, SOFT)

    private val _mode by lazy { MutableStateFlow(normalize(Ctx.prefs.getString(KEY, AUTO))) }

    /** 当前解码方式 (设置页观察). */
    val mode: StateFlow<String> get() = _mode.asStateFlow()

    val current: String get() = _mode.value

    /** 全部可选项 (设置页单选列表). */
    fun options(): List<String> = ORDER

    /** 内核名 (错误提示/关于页展示). */
    fun kernelName(value: String = current): String = "ExoPlayer"

    private fun normalize(value: String?): String = if (value in ORDER) value!! else AUTO

    fun setMode(value: String) {
        val v = normalize(value)
        _mode.value = v
        Ctx.prefs.edit().putString(KEY, v).apply()
    }

    fun label(value: String): String = when (value) {
        HARD -> "硬解"
        SOFT -> "软解 (FFmpeg)"
        else -> "自动"
    }

    /** 一句话说明 (设置页列表副标题, 保持单行可读). */
    fun description(value: String): String = when (value) {
        HARD -> "只用硬解, 无回退"
        SOFT -> "只用 FFmpeg 软解, 无回退"
        else -> "硬解优先, 失败逐级降级"
    }

    /** 详细说明 (设置页主项副标题). */
    fun detail(value: String): String = when (value) {
        HARD -> "只用硬件解码器, 失败即报错"
        SOFT -> "只用内置 FFmpeg/dav1d 解码器 (H.264/H.265/VP8/VP9/AV1), 失败即报错"
        else -> "硬解 → 软解 → FFmpeg 逐级回退"
    }
}

/**
 * 每个 ExoPlayer 一个: 基于 NextLib 的 [NextRenderersFactory] + [DecoderManager]
 * 管理 FFmpeg/dav1d 扩展渲染器与 MediaCodec 的选择和运行时切换.
 *
 * 三档设置的映射:
 *   auto → NextDecoderMode.AUTO      (轨道级: 硬 MediaCodec → 软 MediaCodec → FFmpeg 自动兜底);
 *   hard → NextDecoderMode.HARDWARE  (只用硬 MediaCodec);
 *   soft → NextDecoderMode.FFMPEG    (只用 FFmpeg/dav1d 渲染器).
 * 音频固定 AUTO, 系统不支持的音频编码 (AC3/EAC3/DTS/TrueHD...) 由 FFmpeg 渲染器接管.
 *
 * 自动模式的运行时升级链 (NextLib 不自动恢复运行时解码失败, 由这里接管):
 *   AUTO → 强制软 MediaCodec → 强制 FFmpeg → 报错.
 * 切换用 [DecoderManager.selectVideoDecoder], 内部处理 stop/prepare, 位置由调用方恢复.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DecoderController {

    /** 自动模式升级阶段: 0=自动链 1=已强制软 MediaCodec 2=已强制 FFmpeg. */
    @Volatile
    private var escalation = 0

    /** NextLib 的解码器管理器 (视频/音频模式独立切换). */
    private val manager = DecoderManager()

    /** 创建按解码方式配置好的 ExoPlayer (点播/直播共用). 必须在主线程调用. */
    fun createPlayer(): ExoPlayer {
        val renderers = NextRenderersFactory(Ctx.app)
            .setDecoderManager(manager)
            // 同类解码器列表内初始化失败时依次尝试下一个 (例如多个硬件解码器)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(Ctx.app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PlayerDataSourceFactory))
            .build()
        manager.attach(player)
        manager.selectVideoDecoder(targetMode())
        manager.selectAudioDecoder(NextDecoderMode.AUTO)
        return player
    }

    /** 当前设置对应的视频解码模式 (含自动模式的升级阶段). */
    private fun targetMode(): NextDecoderMode = when (DecoderPrefs.current) {
        DecoderPrefs.HARD -> NextDecoderMode.HARDWARE
        DecoderPrefs.SOFT -> NextDecoderMode.FFMPEG
        else -> when (escalation) {
            1 -> NextDecoderMode.SOFTWARE
            2 -> NextDecoderMode.FFMPEG
            else -> NextDecoderMode.AUTO
        }
    }

    /** 即将播放新的媒体项 (换集 / 换台 / 换源 / 重试): 自动模式重新从硬解开始; 设置有变则应用新设置. */
    fun newItem() {
        escalation = 0
        // 模式没变时 select 内部 no-op; 变了会 stop+prepare, 随后 open() 反正要重新 prepare
        manager.selectVideoDecoder(targetMode())
    }

    /** 给错误提示用: 当前实际使用的解码方式. */
    fun describe(): String = when (DecoderPrefs.current) {
        DecoderPrefs.HARD -> "硬解 (无回退)"
        DecoderPrefs.SOFT -> "FFmpeg 软解 (无回退)"
        else -> when (escalation) {
            1 -> "自动 (已切换软 MediaCodec)"
            2 -> "自动 (已切换 FFmpeg)"
            else -> "自动 (硬解优先)"
        }
    }

    private fun isDecoderError(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

    /** 自动模式且尚未降到底: 视频解码器强制降一级并从当前位置重新准备. */
    private fun escalateAndRetry(player: ExoPlayer): Boolean {
        if (DecoderPrefs.current != DecoderPrefs.AUTO || escalation >= 2) return false
        escalation++
        val target = targetMode()
        val live = player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET
        val pos = player.currentPosition
        manager.selectVideoDecoder(target)
        // select() 内部会在播放中 stop+prepare; 若出错时已处于 IDLE 则这里补 prepare
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (!live) player.seekTo(pos) else player.seekToDefaultPosition()
        player.playWhenReady = true
        AppSnackbar.show(if (escalation == 1) "硬解失败, 已切换软解" else "软解失败, 已切换 FFmpeg 解码")
        return true
    }

    /**
     * 播放器报错时调用. 返回 true = 解码器相关错误且已在自动模式下降级重试,
     * 调用方不要再当作失败处理; false = 按原有流程处理错误.
     */
    fun onPlayerError(player: ExoPlayer, error: PlaybackException): Boolean {
        if (!isDecoderError(error)) return false
        return escalateAndRetry(player)
    }

    /** [onTracks] 的结果. */
    enum class TracksCheck { OK, UNSUPPORTED }

    /**
     * 轨道变化时调用. 启用的渲染器里没有任何一个支持该视频轨 (轨道被静默丢弃, 只播声音):
     * 自动模式的轨道级兜底 (硬→软→FFmpeg) 由 NextLib 自动完成, 走到这里说明全部不支持,
     * 直接按失败处理让调用方报错.
     */
    fun onTracks(player: ExoPlayer, tracks: Tracks): TracksCheck {
        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (!hasVideo || tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)) return TracksCheck.OK
        return TracksCheck.UNSUPPORTED
    }

    fun unsupportedMessage(): String = "当前设备没有可用于该视频的解码器\n解码方式: ${describe()}"
}
