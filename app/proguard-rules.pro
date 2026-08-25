# 阅读书源去重 ProGuard / R8 规则
# 启用代码压缩（tree shaking）但不混淆，保持可读堆栈便于排查

# 保留所有 Java 源文件中的类（不混淆）- 已覆盖 model、ui、check 等所有子包
-keep class com.mina.yuedu.** { *; }

# 保留 WebView JavaScript 接口
-keepclassmembers class com.mina.yuedu.ui.YckBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 Android 组件
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.webkit.WebViewClient { *; }

# 保留 ZXing 核心库
-keep class com.google.zxing.** { *; }