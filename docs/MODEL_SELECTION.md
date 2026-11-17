# 手机端模型

v0.3.0 支持 LFM2.5-350M INT4（248 MB）和 LFM3-1.7B 混合 INT4（1.44 GB）。两款模型都通过 LiteRT-LM 在 CPU 上离线运行，应用配置 2,048 token 的上下文，输入为无障碍服务提供的控件文字。默认选中 350M。每条任务使用启动时选中的一款模型。[350M LiteRT 文件](https://huggingface.co/litert-community/LFM2.5-350M) · [1.7B LiteRT 文件和测试数据](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT) · [Android 运行时](https://developers.google.com/edge/litert-lm/android)

在雷电 9 的 Android 9 环境，使用游戏 4.1.2 的角色入口对比「打开客户端设定」。耗时包含模型初始化和生成首条操作。LFM2.5-350M 两次分别用 4.6 秒和 3.5 秒返回，均未生成可执行操作。LFM3-1.7B 的一次测试用 37.1 秒选中「游戏设定」，助手完成点击并核验了「客户端设定」标题。

LFM3-1.7B 这份量化文件的公开解码成绩为 Galaxy S26 CPU 35.53 token/s、GPU 32.42 token/s，以及 Pixel 8a CPU 14.73 token/s、GPU 12.77 token/s。两款设备的 CPU 内存峰值分别约 2.2 GiB 和 1.9 GiB。游戏内的单步耗时还包括模型加载、处理界面文字和核对点击结果。实体手机上的助手任务耗时、内存和发热待实测。[设备测试数据](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT)
