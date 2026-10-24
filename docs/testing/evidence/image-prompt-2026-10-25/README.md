# 图像转提示词：合成素材复核原件

本目录只包含合成几何图、虚构海报、脱敏题答及提示词，不包含账号、会话、密钥、真实人物或用户项目文件。结论见[本机验证报告](../../图像转提示词本机验证-2026-10-25.md)。

| 文件 | 用途 |
| --- | --- |
| `synthetic-reference.png` | 上传给 MiniMax M3 的合成参考图，900×600 |
| `cases.json` | 最终实际 V3 的五个阶段，包含输入、原题、实际回答、观察和正文；资产 UUID 已替换为合成文件名 |
| `reference.prompt.txt` / `reference.png` | 直接还原的最终提示词原文与首次下游成图 |
| `image-confirm.prompt.txt` / `image-confirm.png` | 以几何图为风格参考的薄荷新图提示词与首次成图 |
| `text-confirm.prompt.txt` / `text-confirm.png` | 无图 Plan 的窗边阅读提示词与首次成图 |
| `manifest.json` | 调用数、参数、运行指导版本和 SHA-256、图片与正文哈希、已知与未知成本边界 |

绘图调用只传 `.prompt.txt` 中的原文，不附 `synthetic-reference.png`，也不追加创作要求；使用 `image_gen.imagegen`，`transparent_background=false`。具体绘图模型版本、内部尝试、Token 与金额未由工具公开。

累计 15/20 次：前期实际 V1 七次，最终 V3 五次，绘图三次；未用五次关闭释放。前期文件名中的 V2/V3 归属错误已在报告中更正，不能拿来作候选对照。本目录不包含旧账号私有原件；原日志、运行归属更正和执行入口哈希保留在本机忽略目录。

主体与标题可核对，Plan 重复追问及扁平风格偏差仍存在。本目录不代表完整质量通过、稳定收益或正式发布。
