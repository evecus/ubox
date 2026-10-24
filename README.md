# UBox

TVBox-flavored movie app, rebuilt as a **pure Kotlin + Jetpack Compose** Android app.
Android only, phone + TV (leanback) in one APK.

## Architecture

- **UI**: Jetpack Compose (Material3), ViewModel + StateFlow, 自绘轻量路由返回栈
- **Player**: ExoPlayer (media3) — 点播播放页与直播页共用 `ExoPlayer` +
  自绘 `VideoSurfaceView` (保持视频宽高比) + 控制层/弹幕覆盖.
- **Danmaku**: 弹幕大师 `DanmakuFlameMaster` (与原版 UBox 同一依赖
  `com.github.ctiao:DanmakuFlameMaster:0.9.25`, jcenter 发布, 走阿里云镜像),
  `NovaDanmakuParser` 解析 bilibili XML (含高级弹幕), `DanmakuManager` 负责
  seek 对齐/缓冲冻结/设置热更新 (对照原版 DanmuLoadController).
- **Crawler engine (Kotlin, unchanged)**:
  - `quickjs/` — QuickJS JNI wrapper (verbatim from the original UBox project, must not be modified).
  - `crawler/` — spider engine: `com.mobile.js.*` (JS bridge, QuickJS runtime, okhttp, crypto, trans),
    `com.github.catvod.*` (Spider, JsLoader, JarLoader, LocalServer on port 9978, proxy routing).
  - `app/` — `UBoxApplication` (QuickJSLoader + LocalServer bootstrap),
    `com.me.ubox.engine.EngineClient` — 原 Flutter 版 `ubox/engine` MethodChannel 的
    Kotlin 直连替代 (协程封装 SpiderLoader/LocalServer/OkHttp/ConfigCrypto).

## Engine client contract (`com.me.ubox.engine.EngineClient`)

- `loadSites(sites)` — accepts TVBox sites entries (`key,type,api,ext,jar`).
  Spider 路由对照原版 `ApiConfig.getCSP`: api 以 `.js` 结尾走 jsLoader, 其余 (含 `csp_XXX`) 走 jarLoader.
- `loadMainJar(jar)` — initializes a previously downloaded main jar (同步加载).
  返回空串 = 成功, 非空 = 可读失败原因.
- `invoke(siteKey, method, arg)` — spider calls:
  `home` (arg "true"/"false" = filter) / `homeVod` / `category` / `detail` / `search` / `player` / `live` / `action`.
- `setPlaySource(siteKey)` — 进入/退出播放页时声明当前播放源 (jar 源 do=ck 探测回退定位).
- `proxyUrl(local)` — local proxy base, always `.../proxy?do=js`.
- `getText(url)` — OkHttp 拉取文本, 支持 IDN 中文域名.
- `decrypt(text, key)` — 原版 `FindResult` 的配置解密 (8位**+base64 / 2423 AES-CBC / `;pk;` AES-ECB).
- `download(url, path)` — OkHttp 下载二进制 (jar), 支持 IDN 中文域名.
- `getIp`, `stopAll`, `unload`.

## Config format

Standard TVBox config: `{"spider": "url;md5;...", "sites": [{"key": ..., "type": 0|3|4, "api": ..., "ext": ..., "jar": ...}, ...]}`.
The app downloads the main jar internally and hands the local path to the engine.
Note: the spider URL is fetched as-is — modern configs deliberately disguise the jar as
`.jpg`/other extensions (the bytes are a real APK with `classes.dex`, md5 matches), so
extension-based filtering would kill every source.

导入 (设置页) 支持的形式, 均对照原版 UBox `ApiConfig`:

- 远程 URL (缺省自动补 `http://`), 或直接粘贴配置 JSON 文本 (`{`/`[` 开头或整体 Base64 长串时不走网络, 其余按原版规则补 `http://` 拉取);
- `{"urls": [{"name": ..., "url": ...}, ...]}` 多线路集合: 自动切到第一条, 设置页"线路"列表可切换;
- `{"url": "..."}` 单线包装跳转 (最多 6 层);
- 加密配置: `[A-Za-z0-9]{8}**` + Base64 / `2423` 开头 AES-CBC / `;pk;` 密钥 AES-ECB (引擎侧解密);
- 整体 Base64 / `@` 拼接的多段 Base64 / 带 `//` 注释的轻量 JSON.

主 jar 加载失败不会静默: 具体原因 (下载失败/DexClassLoader 失败/存储目录不可用) 会显示在
设置页状态栏; jar 失败但存在 js/自带 jar 的源时配置仍可导入.

## Page chain

首页 (源选择 + 推荐 + 分类入口) → 全站搜索 / 分类浏览 → 详情页 (选集 / 收藏 / 换源) → 播放页
(续播 / 下集 / headers proxy:// 与 jx 化解析). 直播 (m3u/txt/json 源, 分组/频道/线路, 自动换源).

## Run

仓库根即 Android 工程根 (settings.gradle / app / crawler / quickjs 均在根下).
用 Android Studio (Ladybug+) 直接打开本目录, 或 `./gradlew assembleDebug`
(minSdk 24, compileSdk 35, AGP 8.11.1, Kotlin 2.2.20).
ABI: 默认 `armeabi-v7a,arm64-v8a`, CI 通过 `NOVA_ABI_FILTERS` 覆盖为单架构.

## Release 构建

`./gradlew assembleRelease` 开启 R8 混淆 + 资源收缩 (`minifyEnabled` / `shrinkResources`,
规则见 `app/proguard-rules.pro`: 弹幕大师 / crawler / quickjs / JNI 均已 keep).

签名按优先级自动选择 (无需改代码, 换环境不改配置):

| 场景 | 签名 |
|---|---|
| 环境变量 `RELEASE_STORE_FILE` 存在且文件可读 | 正式 keystore (`RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` 配套注入) |
| 否则本机/runner 有 `~/.android/debug.keystore` | debug keystore 签名 (可直接安装) |
| 都没有 | unsigned (能构建, 不能安装) |

## CI

`.github/workflows/buil.yml` (仓库现有文件名) — 手动 dispatch 触发, `assembleRelease`
(JDK 17), APK 输出 `out/ubox.apk`, 上传 GitHub Release (tag `test`).

正式签名 (可选) 在仓库 Settings → Secrets 配置 4 个值, 配了就走正式 keystore,
不配自动回退 debug keystore:

- `RELEASE_KEYSTORE_BASE64` — keystore 文件的 base64 (`base64 -w0 release.keystore`);
- `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`.

## TV 模式 (手机 / TV 同一 APK 共存)

TV 界面与遥控操作逻辑对照原版 NovaTV (`com.github.tvbox.osc`) 移植:

- **检测**: `TvMode` — leanback feature 或 `UI_MODE_TYPE_TELEVISION`;
  Dart 侧的手动覆盖在这里是设置页 "界面 → TV 模式" (SharedPreferences `tv_mode`: `auto`/`on`/`off`).
  TV 模式启动时强制横屏 (对照原版各 Activity `sensorLandscape`).
- **Manifest**: `leanback`/`touchscreen` 均 `required="false"`, MainActivity 增加
  `LEANBACK_LAUNCHER` category, 电视桌面可直接启动.
- **布局切换** (`TvMode.isTV` 驱动, 整树重建):
  - 根页面: 底部导航 → 左侧 `TvRail` (初始焦点在导航栏, 右键进内容);
  - 首页: 大海报网格 (200px 自适应 / 0.68) + 大字号, 顶栏 chip / 搜索入口 / 收藏历史全部可聚焦;
    选择器 (线路/站源) 由底部弹层改为可聚焦对话框; MENU 键短按换源, 长按 2s 进设置
    (对照 `HomeActivity.dispatchKeyEvent`);
  - 搜索页 (对照 `SearchActivity` + `SearchKeyboard`): TV 三栏 ——
    左栏 = 指定搜索源按钮 + 输入框 + 搜索/清空按钮 + 屏显键盘 (远程搜索/删除 + A-Z + 0-9,
    每行 6 键, 遥控器移动焦点输入); 中栏 = 热词 (豆瓣热播片名, 聚焦即搜);
    右栏 = 源筛选 + 结果, 搜索完成后焦点自动落到首条结果;
  - 详情页: 选集按钮放大可聚焦, 首集自动获焦 (进页按 OK 即播);
  - 播放页: 全屏视频 + 顶部悬浮 (返回/标题/下一集) + 底部自制控制条
    (播放暂停 / 快退快进 10s / 进度滑条, 全部可聚焦) + 横排选集;
    控制层 8s 无操作自动隐藏, 隐藏时 OK=播放/暂停, 左/右=快退/快进 10s, 上/下=唤出控制层;
  - 收藏/历史/设置: 列表项焦点白描边, 按钮放大, 设置页动作按钮可聚焦.
- **焦点样式**: `TvFocusable` —— 焦点态白色 2dp 描边 + 4% 放大 (160ms) + 白色光晕阴影
  (对照原版 `shape_user_focus` 与 Flutter 版全局焦点环), OK/确定键 (enter/select/space/
  dpadCenter/gameButtonA) 与触摸点击等价; 手机模式自动退化为普通 clickable.

## 弹幕 (手机 / TV 通用, 弹幕大师 DanmakuFlameMaster)

- **渲染**: `master.flame.danmaku.ui.widget.DanmakuView` (与原版 UBox 同一依赖),
  Compose 层用 `AndroidView` 承载, 顶部边距用 `Modifier.offset` 整体下移
  (对照原版 `FrameLayout.LayoutParams.topMargin`: view 高度铺满, 行数/边距互相独立).
  防重叠/行数/描边/行距在 `DanmakuManager.applySettings` 里配置.
- **加载**: `DanmakuApi` —— 默认走内置 echo-danmaku 服务
  (`logvardanmu.konfan.cn`), 按 片名+集名 自动匹配剧集并取评论转 bilibili XML;
  支持自定义接口 (`{name}`/`{episode}` 占位 GET 或 POST), 也支持"云搜"手动选源.
  `NovaDanmakuParser` 在 IO 线程拉取 (gzip/deflate/裸 deflate 解压) + XmlPullParser
  解析 (非法实体兼容 + 正则兜底), 主线程 `prepare` (对照原版 Parser/Danmu).
- **同步**: 库自带时间轴 (内部 SystemTimer), `DanmakuManager` 只在 seek 跳变 (>1s)
  或首次就绪时 `seekTo+start` 对齐, 缓冲/暂停时 `pause` 冻结, `resume` 续进
  (对照 NovaTV `DanmuLoadController`).
- **设置生效**: 参数 (速度/透明度/字号) 实时热更新; 布局类参数 (行数/边距/字号缩放)
  在弹幕设置弹窗关闭时按原源 reload 重备 (对照原版 DanmuFullSettingDialog 关闭时
  post reload=true); 关闭开关即 release 收画面.
- **设置** (设置页 "弹幕" 分区, 播放页控制条/AppBar 亦有入口):
  - 开关 (开启后播放时自动搜索加载);
  - 字号 0.4x~2.4x / 行数 1~30 / 速度 (超慢~快) / 透明度 10%~100% /
    行间距 0~32px / 顶部边距 0~300px;
  - 随机颜色 (16 色调色板, 忽略原配色);
  - 弹幕地址 (默认 / 自定义).
- **TV 操作**: 播放页控制条有 弹幕开关 / 弹幕设置 / 弹幕云搜 三个可聚焦按钮;
  遥控 MENU 键短按切换弹幕开关, 长按 2s 打开弹幕设置 (对照原版 `toggleDanmu`).
