# 开发说明

需要 Python 3.10+ 和 Android Platform Tools。项目只使用 Python 标准库。

```powershell
python -m nitan_assistant devices
python -m nitan_assistant inspect
python -m unittest discover -s tests -v
```

Windows 找不到 `adb.exe` 时，脚本尝试通过已安装的 Ubuntu WSL 发行版调用 ADB。ADB 必须实际能看到授权设备；WSL 与 Windows 的 USB/模拟器网络连接方式取决于本机配置。通过 `NITAN_ADB` 或 `--adb` 可指定 ADB 路径。

截图和 hierarchy 文件可能包含个人信息，默认保存在 `.local/` 并排除在 Git 外。分享前请人工检查。
