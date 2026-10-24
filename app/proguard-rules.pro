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

# quickjs 引擎 (JNI)
-keep class com.novabox.quickjs.** { *; }
-dontwarn com.novabox.quickjs.**

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
