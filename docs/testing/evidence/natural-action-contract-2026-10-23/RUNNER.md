# 本轮真实对照执行入口

复用前轮自然增强计数、账号、真实服务与历史检查；只启动独立回环 9003，Flyway 和账号注册关闭。不得停止或接管 9000。主协议和阶段协议由任务负责人冻结，本入口不新增预算授权。

阶段目录 `<stage>` 需具备 `protocol.json`、`cases.json`、`offline-validation.json`。阶段协议沿用前轮字段，明确 `versions`、各阶段上限、全部 `allowedJobs`、固定模型和参数。开发对照为 4/24/27，留出为 4/27；原始臂只参与同信息下游执行。单个阶段内源码及运行副本全部冻结，既有结果不覆盖，既有调用不重试采样。

```powershell
pwsh -NoProfile -File docs/testing/evidence/natural-action-contract-2026-10-23/start-evaluation.ps1 -Stage development
pwsh -NoProfile -File docs/testing/evidence/natural-action-contract-2026-10-23/invoke-evaluation.ps1 -Stage development -Command generate
pwsh -NoProfile -File docs/testing/evidence/natural-action-contract-2026-10-23/invoke-evaluation.ps1 -Stage unseen -Command confirm
pwsh -NoProfile -File docs/testing/evidence/natural-action-contract-2026-10-23/invoke-evaluation.ps1 -Stage development -Command execute
pwsh -NoProfile -File docs/testing/evidence/natural-action-contract-2026-10-23/invoke-evaluation.ps1 -Stage development -Command count
python docs/testing/evidence/natural-action-contract-2026-10-23/export-stage.py development
pwsh -NoProfile -File docs/testing/evidence/natural-action-contract-2026-10-23/stop-evaluation.ps1 -Stage development
```

调用真实登录后仍需正常识别验证码。合成账号固定引用 `tmp/local-acceptance/account.json`，密码由既有 DPAPI 文件解密后只进入子进程环境；模型密钥继承用户环境，不读取 `.env`，不打印或保存凭据。没有本地代理：临时服务通过已配置 OpenAI 兼容服务端路由调用冻结端点。

证据路径：

- `tmp/natural-action-contract-2026-10-23/<stage>/traces/*.json`：每次网络尝试先占实际预算，保留上游请求、原始响应正文、内部任务记录、错误及耗时；隐藏推理字段剔除，认证头不捕获。
- 同目录 `results/*.json`：业务请求、HTTP 结果、最终优化提示词或作品。正常增强仍真实保存优化历史。
- 公共阶段目录 `history-checks/*.json`：复制正文/存储正文、版本与约束一致性。
- 闭批导出 `transports.jsonl`、`http-results.json`、`prompts/`、`works/`、`cost-and-delivery.json` 和 `closure.json`。闭批后入口禁止继续调用。

## 本轮评测工具偏置修正

旧下游 system 为“执行用户请求并交付其要求的作品。无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。”它预设每个需求都有作品，可能把纯理解任务推向写文档，无法作为本轮中性执行条件。

本轮三类臂统一使用：“完成用户当前请求，遵守用户明确的输出范围、输出形式与阶段边界。仅使用本次提供的材料；无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。”这项评测工具修正适用于全部对照，不能计为候选策略本身的收益；旧历史作品保留原始条件，不与本轮作品混算。

每个付费尝试（失败及协议修复亦同）由服务端先预留记录。计数汇总当前任务所有阶段的 `traces/*.json`，不复用旧任务余额。`count` 只读本阶段统计，最终总账另按全部阶段记录核算。无质量重试。协议可靠性、原意与硬要求保真、作品必要修改量分别判断。

## Plan 路径

开发阶段 `confirmedPlanGeneration: []` 只评估真实提问，版本之间独立生成 Plan。完整阶段使用 `confirmationMode: "PER_VERSION"`，每个版本绑定自己的真实 Plan；`cases.json` 中 `planExpectation: "ZERO_QUESTIONS"` 的任务允许真实零问题、空答案后生成，`"IMPORTANT_UNKNOWN"` 任务必须出现实际问题才绑定冻结事实。意外零问、无法合法答复或 Plan 失败均保留路径失败记录，不模拟题目、不补造答案、不重试到预期题型。

`generate` 完成直接增强与 Plan 提问后即停，尚不确认；负责人检查实际问题，将题号映射到题目事前冻结的 `answerFacts`，写入阶段目录 `answers-by-version.json`，再运行 `confirm`。映射格式为 `{ "CASE_ID": { "4": [{ "questionId": "实际题号", "factIds": ["事前事实ID"] }], "27": [...] } }`。事实为 `{ "id": "F1", "object": "对象", "answer": "完整答复原句" }`。每题只绑定其相关事实，runner不自行猜测、增加事实或把完整答复复制给每题；完整映射和真实绑定在 `plan-paths/` 保存。额外问题没有已授权事实时保留失败，不为通过而伪造答案。

下游原始臂和成功增强臂获得相同完整材料与冻结 `answerBundle`（须包含所有冻结答复事实）；实际问题文本及 planId 的差异属于 Plan 行为，不宣称最终生成的 HTTP 请求逐字一致。最终报告仍须记录增强遗漏的重要问题，不能因下游额外收到完整资料就将 Plan 判为通过。
