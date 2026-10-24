package com.novabox

import android.app.Application
import com.github.catvod.server.LocalServer
import com.mobile.js.App
import com.novabox.data.Ctx
import com.novabox.data.HomePrefs
import com.novabox.data.live.LiveStore
import com.novabox.danmaku.DanmuSettings
import com.whl.quickjs.android.QuickJSLoader

/**
 * NovaBox 纯 Android (Jetpack Compose) 版 Application.
 *
 * 与 Flutter 混合版的差异:
 *   - 不再有 Flutter embedding / MethodChannel, 引擎调用走 [com.novabox.engine.EngineClient]
 *     直连 SpiderLoader / LocalServer / OkHttp / ConfigCrypto (协程封装).
 *   - QuickJS 运行时与本地代理服务 (端口 9978) 的启动顺序保持不变.
 *   - TV 检测 / 弹幕设置 / 首页偏好 / 直播源 均在首帧前完成加载
 *     (对照 Flutter 版 main.dart 的 await 初始化序列).
 */
class NovaboxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Ctx.app = this
        App.shared = this
        QuickJSLoader.init(true)
        LocalServer.start()
        // 以下为轻量 SharedPreferences 读取, 同步完成即可.
        TvMode.init()
        DanmuSettings.load()
        HomePrefs.load()
        LiveStore.init()
    }
}
