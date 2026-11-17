# 开发说明

## Python

需要 Python 3.10+ 和 Android Platform Tools。项目只使用 Python 标准库。

```powershell
python -m nitan_assistant devices
python -m nitan_assistant inspect
python -m unittest discover -s tests -v
```

Windows 查找 PATH、SDK 和雷电安装目录的 ADB，最后尝试 Ubuntu WSL。通过 `NITAN_ADB` 或 `--adb` 可指定 ADB 路径。

截图和 hierarchy 文件可能包含个人信息，默认保存在 `.local/` 并排除在 Git 外。分享前请人工检查。

## Android

需要 JDK 17 或更新版本、Android SDK Platform 35、Build Tools 35.0.0、NDK 28.2.13676358 和 CMake 3.22.1。设置 `ANDROID_HOME` 指向已安装的 SDK，然后在仓库根目录运行。首次配置 CMake 时会下载并校验固定版本的 llama.cpp 源码。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS 和 Linux 使用 `./gradlew`。产物路径为 `app/build/outputs/apk/debug/app-debug.apk`，由 Android Gradle Plugin 的调试证书签名。

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在设备上通过助手首页打开系统设置并启用服务。从角色入口测试，查看日志中的「游戏设定」点击、「客户端设定」出现、「返回」点击、「进入游戏」出现，以及「任务完成」。看到连接弹窗时先处理游戏连接，再从角色入口启动任务。

本地 AI 需要 `arm64-v8a` 或 `x86_64` 和已通过应用校验的 [GGUF 模型](MODEL_SELECTION.md)。可在角色入口输入「打开客户端设定」来验证模型推理、点击和页面核对。输入「打开游戏设定」可验证唯一同名按钮的直接定位。同名按钮、控件别名、页面变化、暂停和无效模型指令由 `AiRunnerTest` 覆盖。

## Codemagic

1. 登录 Codemagic，在 Applications 中选择 **Add application**，连接 GitHub。给 Codemagic GitHub App 授权访问 `mingshenhk/123`，然后选择此仓库。
2. 打开该应用的 **Start new build**，选择 `dev` 分支及 `Android Debug APK` 工作流，再启动首次构建。
3. 在构建详情的 **Artifacts** 下载调试 APK，并查看测试 XML、Lint 报告和 Gradle 日志。确认 GitHub 集成有可用的 webhook 后，向 `dev` 或 `main` 推送会触发构建。

工作流使用仓库根目录的 `codemagic.yaml`。本地已验证同一组 Gradle 任务，首次 Codemagic 云端记录仍待核验。[Codemagic Android 构建](https://docs.codemagic.io/yaml-quick-start/building-a-native-android-app/) · [自动触发构建](https://docs.codemagic.io/yaml-running-builds/starting-builds-automatically/)
