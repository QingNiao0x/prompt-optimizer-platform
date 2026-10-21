# 自然增强实用性与成本收敛证据

第五版候选不替换默认第四版。五组直接增强Token下降24.8%，没有证明稳定提速或作品质量提升；确认后仍有来源重试及再确认风险。完整解释见[评测报告](../../自然增强实用性与成本收敛-2026-10-21.md)。

- 范围及额度：[冻结协议](./protocol.json)、[同批C2协议](./protocol-candidate2.json)、[题目材料](./cases.json)、[29/32次闭批](./closure.json)、[正式与实际总账复核](./budget-limit-audit.json)。正式总上限682、累计实际659，余3次不转移。
- 原件：[29次传输](./transports.jsonl)、[27个业务作业](./http-results.json)、[首阶段26次快照](./transports-candidate1.jsonl)。014／015最终失败和028修复前响应保留，失败和重试全部计数。
- 结果：[成本及缓存](./cost-analysis.json)、[作品与来源审计](./artifact-audit.json)、[逐项人工判断](./manual-assessment.json)、[首阶段历史](./history-checks.json)、[C2历史](./history-checks-candidate2.json)。当前开发代理核对，不是独立双人或专业验收，无模型评审调用。
- 离线：[红例](./regression-red.json)、[首次夹具失败](./affected-first-run.json)、[C1](./offline-validation.json)、[C2](./offline-validation-candidate2.json)、[默认分发夹具错误](./default-selection-first-run.json)、[最终166项](./final-validation.json)。不提交可能含环境属性的完整Surefire XML；实际能保留的摘要／哈希与无法保留的原XML分别说明。
- 冻结：[C1清单](./source-manifest.json)、[C2清单](./source-manifest-candidate2.json)、[C1启动前](./environment-before.json)、[C2启动前](./environment-before-candidate2.json)、[停止自建进程后](./environment-cleanup.json)。运行副本在忽略的tmp，不作为源码提交；当轮清单与最终默认回退差异见最终检查，不能拿最终源码冒充当轮运行字节。
- 机制审查：[版本及边界核对](./mechanism-audit.json)。第四版四个指导常量逐项与基线一致，第一至第三版源码未变；来源编号不等于语义等价，不扩大删除或确认范围。

| 作品 | 原始 | 第四版 | 第五版 |
| --- | --- | --- | --- |
| 严格JSON | [作品](./works/SUFFICIENT_JSON_RAW_EXEC.json) | [作品](./works/SUFFICIENT_JSON_V4_EXEC.json) | [作品](./works/SUFFICIENT_JSON_V5_EXEC.json) |
| 改进建议 | [作品](./works/VAGUE_REPORT_RAW_EXEC.md) | [作品](./works/VAGUE_REPORT_V4_EXEC.md) | [作品](./works/VAGUE_REPORT_V5_EXEC.md) |
| 六页PPT内容稿 | [作品](./works/DELEGATED_PPT_RAW_EXEC.md) | [作品](./works/DELEGATED_PPT_V4_EXEC.md) | [作品](./works/DELEGATED_PPT_V5_EXEC.md) |

`analyze-results.py --verify`只读重算并逐字节核对已存成本、作品与来源，不调用模型、不覆盖原件。首次生成模式只允许新增，现有证据不应删除后重造。`analyze-existing.mjs`的字符投影不是实际Token结论。

启动及调用脚本复用主工作区固定合成账号和DPAPI凭据，不创建替代账号、不记录密钥。Java评测入口不进入生产源码。当前批次已关闭，脚本不能用于继续本批真实调用。
