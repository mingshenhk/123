# Nitan MUD Assistant

泥潭伏魔传的规则脚本阶段。当前版本在电脑上通过 ADB 读取 Android 无障碍节点、检查前台应用、保存截图，并按明确的文字选择器执行单步点击。它不修改游戏、不读取游戏私有数据，也不包含 AI 自动决策。

## 当前状态

- 已实现：ADB 设备枚举、设备/游戏状态检查、UI hierarchy 导出与解析、截图、受控文字点击、JSON 步骤执行、危险界面拦截、仅记录操作步骤的本地日志。
- 已实测：雷电 9（Android 9）中的游戏 4.1.2 可读取角色入口和客户端设定的控件，自动打开设定并返回角色入口。模拟器显示“未发现角色”，角色内玩法还需要实测。
- 游戏 APK 不属于本仓库。不要把 APK、个人截图、运行日志或账号信息提交到 Git。

## 环境

- Python 3.10+
- Android Debug Bridge (ADB)
- Android 设备或模拟器开启 USB/网络调试并完成授权

Windows 会查找 PATH、Android SDK 和雷电安装目录中的 ADB，再尝试 WSL Ubuntu。可设置 `NITAN_ADB` 指定 adb 可执行文件。

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
