# 自然 37 意图与补全质量评测入口

用户明确授权本轮实施并自主决定真实次数，执行者选择新增任务上限 **128**：`regression` 40、`heldout` 40、`works` 48。历史已闭批累计 **1848** 单列，36 的释放余额不转用，不以用满预算为目标。任务、协议和判据在工程绿测前均为 DRAFT；本入口准备不产生模型调用或服务变更。

## 冻结的采用要求

采用要求以 `adoption-preregistration.json` 和 `task-protocol.json` 为准，启动清单包含这两份文件，不随结果调门槛：

1. 三项严重回归与相反正常变体零严重失败。
2. 独立内部留出零新增严重失败。
3. 同信息实际作品相对 31 至少两题清楚改善，改善体现在必要修改项减少或交付完整性/可执行性提高，其中至少一题来自留出。
4. 相对原始提示词无严重退步；分别报告原始到增强的产品收益与 31 到候选的升级收益。
5. 充分需求持平允许但不计收益，严重意图/事实/授权/硬要求错误不能由其他得分抵消。不满足即不切默认。

模型自报检查、合法 JSON、来源引文存在、HTTP 成功、篇幅增加和框架命中都不能单独证明语义或质量通过。两份作品全部考虑，不挑最好的一份；逐项修改负担须有实际来源和正文片段支持。内部作者看过机制与指导，诚实标记内部留出，不称外部专业认证。

## 最小阶段安排

- 回归正常 24 次、12 个逻辑作业：9 个候选直接增强各 2 次（准备和正文）、2 个 Plan 各 2 次、真实回答后增强 2 次。题目包括三旧失败、三相反正常变体、科研、充分通知，以及用户明确需要模板且没有数据库/日志权限的一题。对照现有证据能回答的问题不重复采样。
- D02/D03/D04 逐字复用旧 31 增强，D02/D03 的原始/31 作品也带原件指针复用用于历史参考；共 11 份逻辑结果、3 份历史检查。36 仅作失败归因，稳定基线为 31。没有旧 31 的题不伪称已有对照，必要时在作品阶段补一次。
- 留出来自新作者封存的两题，旧 `preparation36-holdout` 不读取。候选与判据冻结、回归关闭且独立审查 PASS 后，`prepare-heldout.mjs --release-after-regression-pass` 校验事前保存的两个哈希再打开新题。正常 8 次，仅生成 31/37 提示词；作品另在 works 阶段统一执行。
- 作品阶段在回归和留出均 PASS 后，用 `prepare-works.mjs CASE1,CASE2,...` 选择 2—4 题（至少一题留出），复用两个关闭阶段的增强原件，缺少 31 的题才补生成。原始/31/37 三臂各两份作品，正常 12—24 次执行，外加确实缺失的 31 增强。选题和评审依据先冻结。阶段余量仅用于已冻结故障修复或独立复验，不作质量重试。
- 所有失败、协议修复、执行和付费评审计入任务与阶段上限。当前未分配付费评审，独立代理按原件复核；不得因协议允许 REVIEW 就临时调用。

37 保持准备与正文/Plan 两次调用，无额外模型审阅阶段。`taskScope` 作为辅助，完整原始输入与全部实际题答继续传递。新增职责和双向检查仍在既有同次生成与任务记录内，平台不宣称程序能够证明任意语义正确。

## 输入与运行边界

- 仅白名单业务字段进入增强。`executorMaterials` 不进入增强/Plan，评分卡不进入增强或作品执行；原始/31/37 作品接收逐字相同执行材料、普通背景与答复。
- 题目为用户已给原题或合成夹具，无本仓库业务源码附件、真实患者/客户记录或部署配置；程序仍需在首次真实执行前保存本轮外发范围核对。既有 DeepSeek 端点和服务端凭据用于授权评测，凭据不进入提示词、命令行或公开证据。
- 固定复用 `tmp/local-acceptance/account.json` 与既有凭据，经 CSRF、验证码正常登录，不创建账号或租户。临时 9003 禁止 Flyway，不接管日常端口，不重启原数据库或 Redis。启停均核对主类、私有目录、PID、可执行路径和创建时刻。
- 源码、运行副本、脚本、模型参数、题目及判据冻结后每次外发核对 SHA；任何漂移停止。新阶段在门禁 PASS 且前阶段关闭后才执行。闭批任务或阶段无法重新启动。

## 离线准备与首次执行

```powershell
node docs/testing/evidence/natural-intent-completion-2026-10-24/prepare-regression-reuse.mjs regression
node docs/testing/evidence/natural-intent-completion-2026-10-24/validate-stage.mjs regression --draft
node docs/testing/evidence/natural-intent-completion-2026-10-24/selfcheck-inputs.mjs
# 与生产测试共用 target 时，等待测试结束再编译独立入口。
pwsh -NoProfile -File docs/testing/evidence/natural-intent-completion-2026-10-24/preflight-compile.ps1
```

根代理确认工程绿测后，保存 `regression/offline-validation.json`；保留草案，另写 `protocol.json=FROZEN`，将任务及采用判据一起冻结。首次只启动回归：

```powershell
pwsh -NoProfile -File docs/testing/evidence/natural-intent-completion-2026-10-24/start-evaluation.ps1 -Stage regression
pwsh -NoProfile -File docs/testing/evidence/natural-intent-completion-2026-10-24/invoke-evaluation.ps1 -Stage regression -Command generate
# 只按真实题号绑定事前冻结的答案事实，再执行 confirm。
pwsh -NoProfile -File docs/testing/evidence/natural-intent-completion-2026-10-24/invoke-evaluation.ps1 -Stage regression -Command confirm
```

回归输出齐后暂停，交根代理与独立代理复核。任何严重失败都停止后续阶段，不自动开启留出或作品。每个阶段停止自建进程、核对其他监听未被影响、导出并关闭原件；最终分别报告工程完成、候选采用和默认版本状态。
