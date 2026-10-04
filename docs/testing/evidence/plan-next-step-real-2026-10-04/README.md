# 本轮真实模型证据

全部任务资料为合成内容。真实调用的模型为当前默认DeepSeek-V4.1-Flash；正式业务验收未通过，独立专业评审PENDING。

- `inputs.json`：冻结需求、文件、预先检查标准与答案卡；oracle未发送给模型。
- `api/`：八题Plan、实际提交答案、上下文分析、直接与最终结果；剔除contextId、planId，不含认证信息。
- `api-summary.json`、`requests.json`：结构计数和请求标识；24次生成全部成功不代表内容正确。
- `telemetry.json`：日志白名单元数据，嵌套阶段不可相加；仅关联本轮请求。
- `execution/`：十份下游实际作品及参数、用量、完成原因。PARTIAL不得按完整交付通过。
- `blind/`、`review-template.json`：匿名作品与待评审表。评审不应读取`blind-mapping.json`、API结果或组别资料。
- `unreviewed-report/`：未评分报告，胜率为空；本轮AI审阅非盲评，不能代替专业放行。
- `manifest.json`：导出文件哈希和边界；后续README与人工审阅补充不在初始导出列表中，业务原始证据禁止覆盖。

本轮4项本机HTTP检查证据见`http-concurrency-check.json`，它使用受控HTTP上游，不是付费模型压测。完整结论见[复验报告](../../Plan真实模型复验与中长提示词执行对照-2026-10-04.md)。
