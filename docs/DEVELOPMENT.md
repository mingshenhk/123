# 开发说明

需要 Python 3.10+ 和 Android Platform Tools。项目只使用 Python 标准库。

```powershell
python -m nitan_assistant devices
python -m nitan_assistant inspect
python -m unittest discover -s tests -v
```

Windows 查找 PATH、SDK 和雷电安装目录的 ADB，最后尝试 Ubuntu WSL。通过 `NITAN_ADB` 或 `--adb` 可指定 ADB 路径。

截图和 hierarchy 文件可能包含个人信息，默认保存在 `.local/` 并排除在 Git 外。分享前请人工检查。
