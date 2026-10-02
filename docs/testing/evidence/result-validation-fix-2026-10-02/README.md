# 结果校验修复证据

本目录的 JSON 均为本轮测试原报告或合成业务请求/响应的原样副本。未保存认证 Header、Cookie 或环境模型密钥；不包含真实用户资料。

| 文件 | 用途 |
| --- | --- |
| `smoke-report.json` | 冒烟原报告，保留两次断言误报产生的失败状态 |
| `smoke-assertion-replay.json` | 修正断言后的 16 条原响应离线复核，记录原报告 SHA-256，不替代真实调用 |
| `first-load-report.json` | 第一轮 100 人复测原报告，保留 Pro 文档规则含义遗漏的失败状态 |
| `final-load-report.json` | 最终代码四个 100 人波次通过的真实付费报告 |
| `first-load-pro-document-20.json` | 实际遗漏的合成文档、业务请求及响应 |
| `final-load-pro-document-20.json` | 最终代码下同编号文档样本的真实请求及响应，供对照 |
| `smoke-pro-plan-document-2.json`、`smoke-pro-confirmed-document-2.json` | 额外模板与具体未决事项质量问题的计划/确认配对证据 |
| `synthetic-fixtures.json` | 中等长度原始提示词、文档和前端索引证据 |
| `regression.json` | 当前源码测试类的回归结果及最后 190 项增量回归、真实 Redis 与离线回放说明 |
| `candidate1-source-manifest.json` | 第一版代码的基线提交与生产文件 SHA-256 |
| `source-manifest.json` | 最终代码的基线提交与生产文件 SHA-256；工作区修改未提交，不能仅凭 Git HEAD 还原最终代码 |

报告中其他 `synthetic-exchanges/...` 引用对应本机 `services/api/target/real-model-concurrency/<runId>/` 下完整归档。此目录只持久保存上表列出的代表性正文，完整报告和所有安全诊断均保留；清理 `target` 会删除未复制的其他合成正文。

本证据支持本次故障修复与所列场景的并发结论，不表示完成全部业务领域、长期稳定性或正式上线语义质量验收。详见[修复报告](../../结果校验与资料规则保留修复-2026-10-02.md)。
