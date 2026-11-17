# 架构

`tasks/check_settings_navigation.json` 是两个运行时共用的任务配置。流程从角色入口开始，打开客户端设定，再返回角色入口。两个执行器都限制任务步数、等待和重试。点击前必须有唯一匹配的可见控件，点击后必须确认新页面文字。

## Python

- `adb.py` 发现设备并采集前台状态、UI hierarchy 或截图。
- `ui.py` 解析 UIAutomator XML，定位匹配节点或最近的可点击祖先。
- `safety.py` 检测人工验证和风险操作词。
- `tasks.py` 运行有界 JSON 规则，`cli.py` 提供命令行入口。

## Android

- `MainActivity` 提供系统无障碍设置入口、任务操作、悬浮控制开关和日志。
- `RuleAsset` 读取 APK 内的共用 JSON，`RuleRunner` 决定等待、点击、暂停和停止。
- `GameAccessibilityService` 在活动任务期间读取目标游戏节点。服务在动作前核对活动窗口、节点和游戏包，再调用系统节点的点击操作。任务结束时移除悬浮控制。
- `RunLog` 保存最近 100 条任务事件到应用私有目录。应用没有网络权限。
