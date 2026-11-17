# Nitan MUD Assistant

泥潭伏魔传的 Android 助手。可以输入自然语言目标，由本地模型读取控件、选择点击并重新读界面，也可以运行预设规则。电脑上的 Python 脚本支持同一份规则配置。

## 当前状态

- 在雷电 9（Android 9）的游戏 4.1.2 上，Python 脚本和 Android 规则任务均完成了「游戏设定 → 客户端设定 → 返回角色入口」，并核验了页面文字。
- 同一环境下，GGUF 版使用「打开客户端设定」连续两次选中「游戏设定」并核验页面，初始化和首条推理耗时分别为 26.1 秒和 27.4 秒。
- 测试环境显示「未发现角色」。角色内任务、实体手机和 Codemagic 云端构建仍待验证。
- 游戏 APK、调试产物、个人截图、运行日志和账号信息保存在 Git 外。

## Android 助手

1. 在 Android 7.0 或更新版本上分别安装游戏和助手调试 APK。
2. 打开助手，点「打开系统无障碍设置」，开启「泥潭伏魔传助手」。返回助手后确认显示「无障碍 已连接」。
3. 把游戏停在显示「进入游戏」和「游戏设定」的角色入口。若出现服务器连接提示，先在游戏里处理。
4. 运行预设规则时，回到助手点「运行规则任务」。游戏内控制条和助手首页均提供暂停、继续和停止。完成后可在助手中查看运行日志。

运行任务无需连接电脑。助手日志只保存任务、操作和错误，保留在本机应用内。遇到人工验证提示时，在游戏中处理后可以手动继续。

### 本地 AI

1. 在 64 位 Android 设备安装 v0.4.0 调试 APK，然后打开助手的「模型下载页」下载 [LFM3-1.7B-Q4_K_M.gguf（约 1.10 GB）](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/blob/b3f2ae5d73cd3c91bf40e8ba752c648aa05dffdf/LFM3-1.7B-Q4_K_M.gguf)。
2. 点「导入模型」并选择 GGUF。助手会校验文件并复制到应用存储，复制需要额外约 1.10 GB。看到「模型已就绪」后可以从下载目录删除原始副本。从旧版升级也需要导入这份 GGUF。
3. 确认无障碍已连接，在角色入口输入「打开客户端设定」，点「运行 AI 任务」。输入与按钮同名的「打开游戏设定」可测试直接定位。

助手通过 llama.cpp 在设备 CPU 上离线推理。它读取游戏暴露的无障碍文字和可点击控件，每次点击后重新读取界面。纯图片或没有暴露文本的控件目前无法识别。运行配置和实测见 [模型说明](docs/MODEL_SELECTION.md)。

## Python 脚本环境

- Python 3.10+
- Android Debug Bridge (ADB)
- Android 设备或模拟器开启 USB/网络调试并完成授权

Windows 会查找 PATH、Android SDK 和雷电安装目录中的 ADB，再尝试 WSL Ubuntu。可设置 `NITAN_ADB` 指定 adb 可执行文件。

## Python 脚本用法

在仓库根目录运行：

```powershell
python -m nitan_assistant devices
python -m nitan_assistant inspect
python -m nitan_assistant dump-ui
python -m nitan_assistant screenshot
```

多台设备时使用 `--serial` 指定设备。截图和 UI hierarchy 默认写入被 `.gitignore` 忽略的 `.local/`。

只有在观察到明确、可访问且非危险的按钮后，才可以按精确文字点击；点击必须提供预期结果文本：

```powershell
python -m nitan_assistant tap --text "按钮文字" --expect "点击后应出现的文字"
```

把游戏停在显示“进入游戏”和“游戏设定”的角色入口，运行已实测的设定往返。

```powershell
python -m nitan_assistant run --task tasks/check_settings_navigation.json
```

任务 JSON 支持 `wait_text` 和 `tap_text`。只有唯一的可见控件且预期文字尚未出现时才会点击一次。遇到人工验证提示，先在设备上手动处理，随后按 Enter 让脚本重新读取界面。按 `Ctrl+C` 停止。操作记录保存在 `.local/nitan-assistant.log`。

## 检查

```powershell
python -m unittest discover -s tests -v
```

当前规则脚本不需要 Python 第三方包。

Android APK 的本地构建和 Codemagic 设置见 [开发说明](docs/DEVELOPMENT.md)。
