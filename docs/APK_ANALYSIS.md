# APK 静态分析

检查对象：用户提供的 `C:\Users\xinha\Downloads\泥潭伏魔传.apk`。原 APK 未复制到项目，也未提交。

## 已验证

- `aapt dump badging`：包名 `com.palmmud.xyjclient`，应用标签“泥潭伏魔传”，版本名 `4.1.2`，版本码 `412`。
- Manifest：最低 SDK 24，目标 SDK 36；主入口 Activity 为 `com.palmmud.xyjclient.MainActivity`。
- Manifest 声明网络访问、网络状态、Wi-Fi 状态权限。可见 WeChat 支付入口 Activity，但本项目不调用支付功能。
- APK 含 `assets/index.android.bundle`（约 2.07 MB）以及 React Native/Hermes 相关 native libraries；支持 arm64-v8a、armeabi-v7a、x86、x86_64。按包结构可确认客户端使用 React Native/Hermes 组件。
- APK SHA-256：`b4611a12b5ea3bf20158dc6981035625ba640bf3345dc15ce27948abfcf63c3d`。

## 静态推测与限制

- 游戏主界面可能由 React Native 自绘或复合视图呈现；仅凭 APK 静态信息无法确认 UIAutomator 是否能读到游戏文字、按钮及坐标。
- Bundle 中的可见 UTF-8 文本扫描未发现任务清单中提及的关键字。这不能证明文字不存在：资源可能经过压缩/编码、由服务端下发，或在运行时生成。
- Manifest/静态包结构不能确认当前游戏状态、网络状态、任务冷却、角色数据或实际界面流程。

## 雷电实测

雷电 9 的 Android 9 实例装有同版本游戏。角色入口能读到“未发现角色”“进入游戏”和“游戏设定”。脚本点击“游戏设定”后读到“客户端设定”，点击“返回”后重新读到“进入游戏”。两个按钮均由当前层级的精确文本和可点击容器定位。

## 推荐路线

第一阶段用标准 ADB + UIAutomator hierarchy 实现观察和精确文本操作。每一步要求唯一控件匹配并验证预期文本；如游戏视图不暴露可访问性节点，则停止规则点击，之后再评估 Android 无障碍服务或经用户授权的截图/OCR。不得将坐标猜测当作识别，不做注入、协议仿真或验证绕过。

## 尚待验证

角色内的主界面和任务流程需要用户在测试设备上手动进入现有角色后采样。登录和人工验证由用户处理。
