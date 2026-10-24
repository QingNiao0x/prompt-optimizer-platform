# 自然35执行责任评测入口（草案，尚未调用模型）

本根使用用户本轮新增授权48次；历史累计1789次单列，上一160任务结算释放的4次不挪用。任务及阶段当前为DRAFT，只有根代理完成源码、题目、参数和离线验证冻结后才能启动。

- `development` 上限24，正常计划16：四题候选生成8（含准备），零问/真未知两次Plan共4，真未知实际回答后的生成2，分页作品2。阶段余量只容纳协议修复及失败，禁止质量重试。原始/31的既有分页作品和三份31生成通过哈希、题目及资料逐字核对后复用，不再计费。
- `heldout` 上限20：独立作者封存两题，候选冻结后开启；双版本增强8加三臂各两次作品12。模型评审未分配额度，采用逐源内部复核，不能称外部专业盲审。未收到题目时本阶段不可校验或执行。
- `repair-reserve` 根预留4尚未分配可执行作业，不能直接使用。若需要，须先说明具体故障并冻结相应阶段/作业，仍受总额48约束。
- D01全球AI题只验证增强是否把查询责任转交用户，不用无联网的执行环境宣称现实全球研究质量。D03完整复用原先的函数契约、源码和输入资料；D04覆盖充分要求保真；MR-D05明确没有录像但可读维修记录；两个Plan开发题区分普通查询与用户保留的新地点。
- 仅普通上下文进入增强；`executorMaterials`不进入准备、正文或Plan；执行时三臂收到逐字相同材料。评分卡仅用于复核。既有闭批记录、脚本、失败和traces均不修改。
- 31上游版本允许31及冻结4；35准备/正文/Plan只能35。新入口允许按冻结`planVersions`仅检查候选Plan，不把未运行31误报为本轮对照。
- 真未知先获得实际问题，人工按语义将题号映射到事前冻结`answerFacts`，写入`answers-by-version.json`后运行confirm；不得把完整答复复制给无关问题，不得为漏问虚构确认。零问题、真实未知、实际回答后三条路径分别记录。
- 使用固定合成账号、实际CSRF和验证码；脚本不打印凭据，不读取`.env`；临时9003独立进程关闭Flyway，不接管日常9000。启停同时核对可执行文件、主类、私有目录、PID和启动时刻。

## 离线准备

```powershell
node docs/testing/evidence/natural-material-responsibility-2026-10-24/prepare-development-reuse.mjs development
node docs/testing/evidence/natural-material-responsibility-2026-10-24/validate-stage.mjs development --draft
node docs/testing/evidence/natural-material-responsibility-2026-10-24/selfcheck-inputs.mjs
pwsh -NoProfile -File docs/testing/evidence/natural-material-responsibility-2026-10-24/preflight-compile.ps1
```

上述操作不调用模型。首次复用输出已存在且一致可续查，不覆盖不同内容。编译预检只编译独立入口，不启动服务或读凭据。

## 冻结后执行顺序

根代理先完成35源码的受影响测试和编译，在阶段记录`offline-validation.json`；核对草案后保留`protocol-draft.json`，新增`protocol.json`为FROZEN、填冻结时间，并将任务状态改FROZEN。源码和脚本冻结后再启动，启动器复制实际编译类路径、导出准备/正文/Plan指导、生成哈希清单；运行期间任何漂移均拒绝外发。

```powershell
pwsh -NoProfile -File docs/testing/evidence/natural-material-responsibility-2026-10-24/start-evaluation.ps1 -Stage development
pwsh -NoProfile -File docs/testing/evidence/natural-material-responsibility-2026-10-24/invoke-evaluation.ps1 -Stage development -Command generate
# 读取实际Plan，按冻结事实完成题号映射后继续。
pwsh -NoProfile -File docs/testing/evidence/natural-material-responsibility-2026-10-24/invoke-evaluation.ps1 -Stage development -Command confirm
pwsh -NoProfile -File docs/testing/evidence/natural-material-responsibility-2026-10-24/invoke-evaluation.ps1 -Stage development -Command execute
pwsh -NoProfile -File docs/testing/evidence/natural-material-responsibility-2026-10-24/stop-evaluation.ps1 -Stage development
python docs/testing/evidence/natural-material-responsibility-2026-10-24/export-stage.py development
```

每条实际调用在传输前预留并计数，失败和协议修复都占限额；历史复用结果不复制trace。是否采用35另行根据原件判断，不能仅因上述命令完成或HTTP成功宣布质量通过。
