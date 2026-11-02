# ProGuard / R8 规则 (release 构建: minifyEnabled + shrinkResources)

# ---------------------------------------------------------------- 基础通用规则
# native 方法名必须保留 (JNI 按名字绑定)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Parcelable CREATOR (库内部可能用到)
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# 枚举 valueOf/values (反射场景)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 注解保留
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 源码行号 (崩溃栈可读)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- 弹幕大师
# DanmakuFlameMaster 0.9.25 无 consumer proguard 规则;
# 内部使用 SortedSet/Comparator/反射构造 SpecialDanmaku, 收缩会直接崩.
-keep class master.flame.danmaku.** { *; }
-dontwarn master.flame.danmaku.**

# ---------------------------------------------------------------- 爬虫引擎
# crawler 模块的公共 API (SpiderLoader/Spider/Result 等) 被 app 反射/动态调用;
# 爬虫 jar 是运行时从 assets/网络加载的独立 dex, 不受本规则影响, 但接口必须保留.
-keep class com.github.catvod.crawler.** { *; }
-dontwarn com.github.catvod.crawler.**

# quickjs 引擎 (JNI): 实际包名是 com.whl.quickjs, so 的 JNI 按原类名绑定,
# 类名被 R8 改写后 RegisterNatives/FindClass 失败 → 引擎初始化即崩.
-keep class com.whl.quickjs.** { *; }
-dontwarn com.whl.quickjs.**

# crawler 的辅助包 (com.mobile.js.App 等): 爬虫 jar 的 dex 在运行时直接引用
# 这些类, 不会出现在 app 静态可达图里, 必须显式 keep.
-keep class com.mobile.** { *; }
-dontwarn com.mobile.**

# NanoHTTPD (ControlServer 父类, 内部线程反射读取 header/parms)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ---------------------------------------------------------------- 第三方依赖
# org.json 是 Android 平台类, 无需保留; 若引入 gson/jackson 再按需补充.
-dontwarn org.json.**

# kotlinx.coroutines 自带 consumer rules, 这里只压内部 API 警告
-dontwarn kotlinx.coroutines.**

# okhttp / okio (crawler 传递依赖, consumer rules 已覆盖主要部分)
-dontwarn okhttp3.**
-dontwarn okio.**

# media3 / ExoPlayer (consumer rules 完善, 压警告即可)
-dontwarn androidx.media3.**

# ---------------------------------------------------------------- Compose
# Compose 编译器插件生成的代码有 consumer rules, 无需额外 keep.

# ---------------------------------------------------------------- 动态加载 jar 依赖的公共库 (关键!)
# 爬虫 jar 由 DexClassLoader 运行时加载, 父加载器就是本 App 的 classLoader.
# jar 里的 dex 会直接引用 okhttp3 / okio / jsoup / gson / org.json / kotlin / androidx.collection 等类,
# 但这些引用在 app 的静态可达图里不存在, R8 会把它们整包裁掉或改名
# (表现: APK 只有 ~3MB, jar 源运行时 ClassNotFoundException / NoSuchMethodError).
# 因此这些库必须整包 keep, 不能收缩/混淆/优化.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.gson.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.collection.** { *; }
-keep class fi.iki.elonen.** { *; }
-keep class com.whl.quickjs.** { *; }
-dontwarn kotlin.**
-dontwarn org.jsoup.**
-dontwarn com.google.gson.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------- ViewModel
# viewModel() 经 ViewModelProvider 反射 getDeclaredConstructor 创建实例;
# R8 会改名/删除构造函数 → "Cannot create an instance of class X / NoSuchMethodException: X.<init> []".
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}
-keep class com.me.ubox.**.*ViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory { *; }

# ---------------------------------------------------------------- 引擎整包 keep (搜索/播放失败的根因)
# 爬虫 jar (DexClassLoader) 的 dex 直接调用 App 内 crawler 模块的类:
#   com.github.catvod.net.OkHttp / Net / Proxy / server.* / crawler.* / com.mobile.js.* ...
# 并且 JarLoader/SpiderLoader 反射 jar 内类. 只 keep crawler.** 不够: net.OkHttp、Proxy 等被
# R8 改名/裁剪后, jar 调用 NoSuchMethodError/ClassNotFoundException, 被 EngineClient.invoke
# 的 try/catch 吞掉 → 搜索无结果, 播放拿不到地址/代理端口. 整包保留, 不收缩不混淆.
-keep class com.github.catvod.** { *; }
-keep interface com.github.catvod.** { *; }
-keepclassmembers class com.github.catvod.** { *; }
-keep class com.mobile.** { *; }
-keep interface com.mobile.** { *; }
-keepclassmembers class com.mobile.** { *; }
-keep class com.whl.quickjs.** { *; }
-keep class com.me.ubox.engine.** { *; }

# ---------------------------------------------------------------- IjkPlayer
# ijk 的 Java 层通过 JNI 绑定 native 符号, 类名/方法名被改名后 so 反射不到 (崩溃无堆栈).
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep interface tv.danmaku.ijk.media.player.** { *; }
-keepclassmembers class tv.danmaku.ijk.media.player.** { *; }
-keep class tv.danmaku.ijk.media.widget.** { *; }
