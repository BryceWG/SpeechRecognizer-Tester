# SpeechRecognizer Tester

Android `SpeechRecognizer` API 的最小可视化测试应用，用于验证系统 `RecognitionService` 的发现、绑定与回调行为。工程可作为 SpeechRecognizer 集成与调试的样板项目使用。

## 功能

1. 枚举系统已公开的 `RecognitionService` 列表，并展示服务信息。
2. 以指定 `ComponentName` 创建 `SpeechRecognizer`，便于测试特定识别服务。
3. 支持开关 `RecognizerIntent.EXTRA_PARTIAL_RESULTS`，展示中间结果与最终结果。
4. 支持填写语言标签并传入 `RecognizerIntent.EXTRA_LANGUAGE` 与 `RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE`。
5. 展示 `RecognitionListener` 回调日志，支持复制与清空。
6. 显示 `onRmsChanged` 振幅数值与进度条。
7. 支持 Material3 动态配色与深色模式。

## 环境要求

1. Android Studio
2. Android SDK `minSdk` 29
3. JDK 21

## 构建与运行

1. 使用 Android Studio 打开 `speechrecognizer-tester` 目录。
2. 等待 Gradle Sync 完成。
3. 运行 `app` 配置并安装到设备。

## 使用说明

1. 首次启动后授予麦克风权限 `android.permission.RECORD_AUDIO`。
2. 在 Recognition service 下拉框选择目标 `RecognitionService`。
3. 根据需要启用或关闭 partial results，并可填写语言标签。
4. 点击 Start 开始识别；Stop 触发 `stopListening()`；Cancel 触发 `cancel()`。
5. Results 区域显示中间结果与最终结果；Log 区域记录回调与关键动作。
6. 点击 Copy log 将日志复制到剪贴板，便于贴入 issue 或日志系统。

## 说明

1. Android 11 及以上版本对包可见性有限制；本应用在 Manifest 中声明了 `RecognitionService` 查询，以便列举可用服务。
2. `ERROR_INSUFFICIENT_PERMISSIONS` 常见原因是测试应用与所选识别服务应用的麦克风权限状态不一致；两者均需被授予权限，并在授予权限后至少各自启动过一次。
3. 识别的音频处理行为取决于所选 `RecognitionService` 的实现；本应用仅调用系统 API 并展示回调结果。
4. SpeechReconizer API 官方文档：[Android Developers](https://developer.android.com/reference/android/speech/SpeechRecognizer)
5. RecognitionService API 官方文档：[Android Developers](https://developer.android.com/reference/android/speech/RecognitionService)

## 许可证

许可证声明位于 `LICENSE`。
