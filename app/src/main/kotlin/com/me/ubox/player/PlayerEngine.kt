package com.me.ubox.player

import android.view.Surface

/**
 * 一次播放请求 (点播/直播共用): 地址 + 请求头 + 显式类型 + 起始位置 + 是否直播.
 *
 * [live] 只作内核调参用 (直播走低延迟: 关闭/减小缓冲、减小探测时长),
 * 不参与地址解析.
 */
data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val startMs: Long = 0L,
    val live: Boolean = false,
)

/** 播放内核事件回调 (点播页/直播页共用). */
interface PlayerListener {

    /** 已就绪: 可 seek, 时长可用 (直播可能为 0). 每个媒体项只回调一次. */
    fun onReady() {}

    fun onPlayingChanged(playing: Boolean) {}

    fun onBuffering(buffering: Boolean) {}

    fun onEnded() {}

    /** 播放失败. [message] 已带内核与解码方式信息, 直接展示即可. */
    fun onError(code: Int, message: String) {}

    /** 视频尺寸变化 (宽高 + 像素宽高比; 方形像素为 1f). */
    fun onVideoSize(width: Int, height: Int, pixelRatio: Float = 1f) {}

    /** 提示类信息 (如"硬解失败, 已切换软解"), 用 Snackbar 展示. */
    fun onNotice(message: String) {}
}

/**
 * 播放内核抽象: 上层 (点播/直播 ViewModel) 只依赖本接口,
 * 由 [PlayerEngines.create] 按 [DecoderPrefs] 决定实际内核 (ExoPlayer / IjkPlayer).
 *
 * 生命周期: [open] → [play] → … → [stop] (换源) / [release] (退出页面).
 * 表面可随时 [setSurface] 挂载/摘除 (全屏切换、页面重建后重新挂载).
 */
interface PlayerEngine {

    val isPlaying: Boolean

    /** 当前位置 (ms, 未就绪时为 0). */
    val currentPosition: Long

    /** 总时长 (ms); 直播/未知时为 0. */
    val duration: Long

    /** 已缓冲位置 (ms). */
    val bufferedPosition: Long

    var listener: PlayerListener?

    /** 挂载/摘除渲染表面. */
    fun setSurface(surface: Surface?)

    /** 准备播放 (不自动播: 由调用方决定 [play]). */
    fun open(request: PlayRequest)

    fun play()

    fun pause()

    /** 停止当前媒体项 (换源/切 Tab); 表面与监听器保留. */
    fun stop()

    fun seekTo(ms: Long)

    /** 倍速 (1f = 正常). */
    fun setSpeed(speed: Float)

    /** 释放内核 (页面销毁). */
    fun release()

    /** 当前内核 + 解码方式描述 (错误提示用). */
    fun describe(): String
}

object PlayerEngines {

    /** 没有可用视频解码器时的错误码 (与内核无关的统一码). */
    const val ERROR_NO_DECODER = -1000

    /**
     * 按当前解码方式创建内核:
     *   ijk_* → [IjkEngine]; 其余 → [ExoEngine].
     * 解码方式在设置页改动后, 下次创建内核 (进入播放页/直播页) 生效.
     */
    fun create(): PlayerEngine =
        if (DecoderPrefs.isIjk()) IjkEngine() else ExoEngine()
}
