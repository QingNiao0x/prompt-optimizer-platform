# 自然 36 准备阶段判断与路由评测

本任务来自用户“真实评测自行选择次数”的本轮授权，执行者选择新增总上限 **48**。历史已闭批累计 **1828** 单列；任何旧余额不转入。任务及开发协议在源码、离线测试通过前保持 DRAFT，不能启动模型调用。

- 开发阶段上限 24：7 个直接增强（含准备，共 14 次）、2 个 Plan（共 4 次）、1 个真实题答确认后的增强（2 次）、科研和分页各 2 份作品（4 次）。协议修复和失败占同一额度，没有额外重试额度，不做质量重试。
- D01/D03/D04 的 31 增强及分页原始/31 作品复用旧 development；D02 的 31 增强与科研原始/31 作品复用旧 capacity。题目、执行材料、模型参数、公开/私有结果与来源哈希逐一核对。复用原件的既有来源链保留在原件中；只复制业务结果及新的来源指针，不复制 traces，不重开旧批。
- 直接回归包括全球 AI 查询、科研四部分分析、未上传代码、充分通知、明确无录像但有维修记录、明确没有照片和水位/损失记录、熟悉项目并等待。所有开发题均属于已见回归，不称未见题。
- Plan 分别检验已委托查询的零问题路径、用户保留地点的真实未知路径，以及回答绑定后生成。实际题号只允许映射事前冻结的 answerFacts，不为漏问伪造确认，不把完整答案复制给无关问题。
- 36 仍发送 taskScope，但它只辅助诊断。准备阶段 response.basis 误判不能跳过正文；最终任务记录由正文/Plan 依据完整原始来源统一形成。没有 executionResponsibilities、dependency、correction 清单。不增加模型阶段或职业模板。
- 普通 context 进入增强；executorMaterials 仅在下游执行时提供。三臂执行收到相同材料、模型、参数和系统能力。评分卡不向生成或作品执行外发。历史对照日期不同如实报告，不称同期随机实验。
- D01 只检验优化提示词的意图与有用补充，不用离线执行器证明实时全球研究质量。科研资料为合成演练，不声称真实地区、公共卫生结论或专业认证。
- 独立内部留出阶段上限 24；由独立作者封存。开发阶段关闭且 regression-gate.json 明确 PASS、无未解决严重退步，根代理批准开启后才读取题面。验证器、Node 入口及服务器均拒绝绕过开发门禁。留出只支持同一冻结候选，不边跑边改。
- 复用固定合成账号和既有凭据，经真实 CSRF 与验证码登录。临时 9003 进程关闭 Flyway，不接管日常 9000；启停核对 PID、主类、可执行文件、私有阶段目录及创建时间。凭据只通过进程内存传递，不写日志或公开证据。

离线准备（不调用模型，不启动服务）：

```powershell
node docs/testing/evidence/natural-preparation-routing-2026-10-24/prepare-development-reuse.mjs development
node docs/testing/evidence/natural-preparation-routing-2026-10-24/validate-stage.mjs development --draft
node docs/testing/evidence/natural-preparation-routing-2026-10-24/selfcheck-inputs.mjs
pwsh -NoProfile -File docs/testing/evidence/natural-preparation-routing-2026-10-24/preflight-compile.ps1
```

根代理先完成生产源码受影响测试，在开发目录记录 offline-validation.json；保留 protocol-draft.json，将审核后协议另存 protocol.json=FROZEN 并冻结任务协议。启动时导出真实编译的 31/36 准备、正文与 Plan 指导以及默认版本，冻结源码、运行副本、脚本和题目。任何漂移拒绝外发。

```powershell
pwsh -NoProfile -File docs/testing/evidence/natural-preparation-routing-2026-10-24/start-evaluation.ps1 -Stage development
pwsh -NoProfile -File docs/testing/evidence/natural-preparation-routing-2026-10-24/invoke-evaluation.ps1 -Stage development -Command generate
# 核对真实问题，按冻结事实写 answers-by-version.json 后继续。
pwsh -NoProfile -File docs/testing/evidence/natural-preparation-routing-2026-10-24/invoke-evaluation.ps1 -Stage development -Command confirm
pwsh -NoProfile -File docs/testing/evidence/natural-preparation-routing-2026-10-24/invoke-evaluation.ps1 -Stage development -Command execute
pwsh -NoProfile -File docs/testing/evidence/natural-preparation-routing-2026-10-24/stop-evaluation.ps1 -Stage development
python docs/testing/evidence/natural-preparation-routing-2026-10-24/export-stage.py development
```

每次真实传输前先预留，空预留和失败计数，不能通过中断释放。工程通过、协议合法、原文保留或文字变长均不等于质量提升；默认切换需另行核对实际错误、必要修改量和同信息作品收益。
