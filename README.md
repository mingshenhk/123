# Nitan MUD Assistant

泥潭伏魔传的规则脚本阶段。当前版本在电脑上通过 ADB 读取 Android 无障碍节点、检查前台应用、保存截图，并按明确的文字选择器执行单步点击。它不修改游戏、不读取游戏私有数据，也不包含 AI 自动决策。

## 当前状态

- 已实现：ADB 设备枚举、设备/游戏状态检查、UI hierarchy 导出与解析、截图、受控文字点击、JSON 步骤执行、危险界面拦截、仅记录操作步骤的本地日志。
- 尚未验证：本机没有运行中的雷电模拟器或连接的 Android 设备；本 APK 的游戏界面是否向 UIAutomator 暴露文本，以及任何真实游戏任务流程，都需要连接设备后验证。
- 游戏 APK 不属于本仓库。不要把 APK、个人截图、运行日志或账号信息提交到 Git。

## 环境

- Python 3.10+
- Android Debug Bridge (ADB)
- Android 设备或模拟器开启 USB/网络调试并完成授权

本机检查到 WSL 中有 `/usr/bin/adb`，但没有发现雷电模拟器进程或已连接设备。Windows 运行脚本时会优先寻找 Windows ADB；如果未找到，会尝试调用已安装的 WSL Ubuntu 中的 ADB。也可以设置 `NITAN_ADB` 指定 adb 可执行文件。

## 用法

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

任务 JSON 只支持 `wait_text` 与 `tap_text`，每次点击都要求唯一匹配和预期结果，且点击不会自动重试。运行前先复制 `tasks/observe_only.json`，按已观察到的真实界面调整。遇到“捣蛋鬼”或疑似人工验证时，脚本会暂停；你在设备上完成验证并按 Enter 后，它会重新读取界面，确认验证提示消失才继续。它不会自动回答验证题或尝试绕过验证。

```powershell
python -m nitan_assistant run --task tasks/my_task.json
```

按 `Ctrl+C` 停止。运行步骤会追加记录到 `.local/nitan-assistant.log`，该目录已加入 Git 忽略。脚本不自动登录，也不会处理充值、交易、丢弃、删除、重置或其他风险操作。

## 检查

```powershell
python -m unittest discover -s tests -v
```

当前规则脚本不需要 Python 第三方包。
