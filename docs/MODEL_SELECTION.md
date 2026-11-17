# 手机端模型

v0.2.0 使用 LFM3-1.7B 的 LiteRT 混合 INT4 量化文件，约 1.44 GB，配置 2,048 token 的上下文。Android APK 通过 LiteRT-LM 在 CPU 上运行，读取无障碍服务提供的控件文字。LFM3-1.7B 是纯文本模型。[模型说明](https://huggingface.co/LiquidAI/LFM3-1.7B) · [LiteRT 文件和测试数据](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT) · [Android 运行时](https://developers.google.com/edge/litert-lm/android)

同一模型配置的公开解码成绩为 Galaxy S26 CPU 35.53 token/s、GPU 32.42 token/s，以及 Pixel 8a CPU 14.73 token/s、GPU 12.77 token/s。两款设备的 CPU 内存峰值分别约 2.2 GiB 和 1.9 GiB。游戏内的单步耗时还包括模型加载、处理界面文字和核对点击结果。实体手机验收时应记录这些耗时，以及与游戏同时运行的内存峰值和发热情况。[设备测试数据](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT)
