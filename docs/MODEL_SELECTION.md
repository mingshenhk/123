# 手机端模型候选（暂不集成）

当前脚本版不调用模型。后续若加入手机本地模型，建议先基准测试 `LFM2.5-350M` 的 GGUF `Q4_K_M` 量化版：它是指令微调模型，官方资料列出的该量化文件约 229 MB，适合作为低内存/低延迟的起点。若它无法稳定完成界面摘要或结构化建议，再在同一手机上比较 `LFM2.5-1.2B-Instruct` 的 `Q4_K_M`。Liquid AI 的硬件评估指南也建议先以 350M 做 bring-up，再逐步测 1.2B；`Q4_K_M` 是其 CPU/edge 默认评估量化。`llama.cpp` 提供 Android 本地运行方式。

候选不等于已验证：不同手机的 SoC、可用 RAM、散热和运行时差异很大，本机没有提供目标手机规格，也没有在目标手机跑过基准，所以目前不能承诺流畅。正式选型前要在目标手机、游戏同时运行的情况下测模型加载峰值内存、首 token 延迟、持续生成速度和长时间温度。模型只负责低频提供状态摘要/结构化建议；规则层继续校验并执行动作，不让模型直接控制点击。当前脚本阶段不包含模型。

参考：

- [Liquid AI hardware evaluation guide](https://github.com/Liquid4All/docs/blob/main/guides/hardware-evaluation.mdx)
- [Liquid AI model library](https://docs.liquid.ai/lfm/models/complete-library)
- [Liquid AI LFM2.5-350M model card](https://huggingface.co/LiquidAI/LFM2.5-350M)
- [Liquid AI: LFM2.5-1.2B Thinking on device](https://www.liquid.ai/blog/lfm2-5-1-2b-thinking-on-device-reasoning-under-1gb)
- [llama.cpp Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
