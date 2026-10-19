# 目标作品优先的通用自然策略证据

产品提交371c8d50。题目、模型、参数及16次分配在首次调用前冻结；没有按样例定制产品生成分支。NATURAL/2与NATURAL/3使用相同任务资料及实际模型别名，仅比较指导版本。详细结论见[复验报告](../../自然增强目标作品优先与通用策略复验-2026-10-19.md)。

- protocol.json、cases.json、source-manifest.json：预先冻结的题集、调用分配与源码。
- transports.jsonl：全部16次实际传输，增强8、Plan4、作品4；无认证头、凭据或隐藏推理。
- prompt-comparisons.json、plan-results.json：两版增强与Plan响应，未包含账号和私有记录标识。
- message-results.json：实际消息作品及完整回复计数。
- java-acceptance.json：旧版16项独立行为通过；新版原样源码缺少BigDecimal导入而编译失败，未修补。
- cost-observations.json、validation-summary.json：有限样本成本观测、本轮223项受影响Java及范围说明。
- budget-closure.json、environment-after.json：16/16闭批、累计564次，以及本轮自建进程清理。

查询和PPT内容要求有具体化收益，采购重要未知保留并少一项前置问题；源码作品有失败，结果不能推广为全部任务通过。原始响应不被摘要或审阅意见替换。没有AI裁判、Qoder API对照、联网查询作品或实际PPT内容稿验收。

本机使用原合成账号、PostgreSQL及Redis 3.2.100，未新建账户、未使用Docker、未应用Flyway。脚本只用于本批独立回环进程，批次已关闭；凭据和Cookie不在本目录。
