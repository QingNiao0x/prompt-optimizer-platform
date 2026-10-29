# 52 Plan 两种回答路径的有限准备

本目录只增加适配工具，未启动服务、冻结批次或调用模型。原始需求直接复用 51 的 P02 完整原文，保留日期歧义和原文件范围；`P02_CONFIRMED`、`P02_UNKNOWN` 各自新建真实上下文与 Plan，不复用旧确认或改写 TTL。

- `live-browser.mjs <stage> <caseId>` 默认仅检查已冻结的 `captureOnly:true / captureVersions:['52'] / jobs:[] / maximum0` 批次。经另行调度、正常登录后，增加 `--capture-context` 才执行真实 Flash 选择、79 文件目录选择、Worker/IndexedDB 检查及一次确定性上下文准备，最终 Plan POST 返回409并保存实际 DTO，绝不转发模型。
- `build-plan-completion.mjs` 仅从本轮实际 capture、实际52 Plan、明确操作者选择及同材料完整增强输入构造待冻结 GEN。实际响应版本不改名；保留所有实际题答、来源、对话、权限及上下文引用。`UNKNOWN` 必须由实际允许自定义回答的问题，明确提交“今天是否允许尚未决定”，不能合成选项或默认回答。
- `validateIndependentCaptures` 在未来冻结 PLAN 前核对两条请求材料一致、固定身份摘要一致、contextId 不同且尚未过期。实际 Plan 所有权仍由服务端确认；本地字段核对不冒充所有权验证。
- UI 范围止于真实上下文准备与 Plan 请求捕获。后续 Plan 模型调用、操作者记录和回答后增强属于服务协议验证，并未声称完整 UI 答题流程，也未验证最终作品收益。

未来作业名：`P02_CONFIRMED_V52_PLAN` / `P02_UNKNOWN_V52_PLAN`，回答后 `P02_CONFIRMED_V52_GEN` / `P02_UNKNOWN_V52_GEN`；PLAN8、GEN8须分别由基础设施冻结，capture阶段零预算。所有权/过期失败保留，不静默重绑。

离线入口：`node self-test.mjs`，只使用明确标记的本地合成对象，不将其写为真实 capture 或 Plan。
