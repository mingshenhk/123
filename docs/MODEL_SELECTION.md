# 手机端模型候选（暂不集成）

当前脚本版不调用模型。以后如加入手机本地模型，建议先实测 `LFM2.5-1.2B-Instruct` 的 GGUF Q4_K_M 量化版本，作为低频状态摘要/结构化建议模型；常规点击仍由规则执行。Liquid AI 的硬件评估指南建议从 LFM2.5-350M 开始，并将 1.2B-Instruct 作为更高能力候选，同时推荐 GGUF Q4_K_M 用于 CPU/边缘设备评估；`llama.cpp` 提供 Android 接入路线。

候选不等于已验证：不同手机的 SoC、RAM、散热和运行时差异很大，本机没有提供目标手机规格，也没有在目标手机跑过基准。正式选型前应测模型加载峰值内存、首 token 延迟、持续生成速度和长时间温度；不达标再退到 350M，只有确实需要多步规划时再比较 Thinking 变体。

参考：

- [Liquid AI hardware evaluation guide](https://github.com/Liquid4All/docs/blob/main/guides/hardware-evaluation.mdx)
- [Liquid AI model library](https://docs.liquid.ai/lfm/models/complete-library)
- [Liquid AI: LFM2.5-1.2B Thinking on device](https://www.liquid.ai/blog/lfm2-5-1-2b-thinking-on-device-reasoning-under-1gb)
- [llama.cpp Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
