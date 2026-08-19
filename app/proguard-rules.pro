# keep for later minify

# WebView 注入的 JS 接口（YckBridge.addToDedupe / collect）由页面脚本通过 window.YckDedupe 调用，
# 方法名不能混淆，否则收集按钮失效。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 桥接类整体保持（含 JS 入口与 Collector 接口）
-keep class com.mina.yuedu.ui.YckBridge { *; }
