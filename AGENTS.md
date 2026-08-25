# 阅读书源去重 v3.5.0

**仓库：** https://github.com/MIXUULS/yuedu-source-dedupe
**上游：** Mina-kk/yuedu-source-dedupe（remote: upstream）
**目录：** C:\Users\liuss\Desktop\book\yuedu-source-dedupe-main
**环境：** JDK 17 (D:\EXRJ666\jdk)、Android SDK、Gradle 8.7
**签名：** debug(android)、release(yuedu2026, alias yuedu)
**设备：** 64367acb

## 功能清单
- 去重三模式(标准/严格/激进)+规则预设
- 校验五步(搜索/发现/详情/目录/正文)+快速模式+自定义状态码
- 校验历史+增量重验失败源
- 书源健康追踪(评分0-100)+健康看板/报告+同域优化建议+智能导出优质源
- 深色模式+Material 3+代理回退+下载进度
- 状态记忆+URL历史+设置备份还原
- 版本对比(两份文件)+分享(文本/JSON/二维码/链接)+剪贴板导入
- 分类导出(可用/不可用/非HTTP/CSV/合并同域)
- 结果多选批量操作(导出/复制/移除)+变更报告+自动分类标签
- 阅读分支选择+YCK(三站切换/一键收集)+右上角更多菜单
- 过滤区(清理登录源+删除验证码源)
- 合并上游3.0.5: FetchPolicy/GZIP/重试/DNS不重试/BOM清理

## 核心类
- SourceCleaner: cleanLogin+deleteCaptcha 过滤(11项测试)
- SourceHealthTracker: 历次校验记录+评分+建议+JSON持久化
- SourceDiff: 版本对比diff(6项测试)

## 构建
- Debug: `./gradlew assembleDebug`
- Release: `./gradlew assembleRelease`
- 测试: `./gradlew testDebugUnitTest`
- 装机: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

**状态：** 工作区干净，已推送(6a578b2)，v3.5.0 就绪