# 自然增强要求覆盖：冻结输入与评测原件

结论、作品差异、成本和验证边界见[本轮报告](../../自然增强要求覆盖与实际作品复验-2026-10-20.md)。产品提交`280618bb`、协议修正`2c8bb6e7`；本批已经关闭，43/48次，剩余5次不转移。

| 文件 | 内容 |
| --- | --- |
| `cases.json`、`protocol.json` | 首阶段冻结题目、相同资料、模型、参数、预算及停止条件 |
| `protocol-candidate2.json` | 首阶段35次后冻结的有限修正，仍共用48次总上限，不新增任务或模型 |
| `source-manifest.json`、`source-manifest-candidate2.json` | 两阶段源码／运行及脚本哈希；C2只有两个产品文件改变，第三版生成指导仍为基线原文 |
| `transports-candidate1.jsonl`、`http-results-candidate1.json` | 首阶段35次传输及首次成功／失败原件，离线回归直接复用 |
| `transports.jsonl`、`http-results.json` | 完整43次传输及38份业务响应，不含鉴权头或隐式推理内容，均为冻结合成需求 |
| `budget-closure.json` | 实际阶段总账、5次协议修复、0质量重试、关闭余额、历史564与累计607 |
| `prompt-comparisons.json`、`plan-results.json` | 同信息新旧优化提示词及Plan问题，C1失败与当前选用C2编号同时保留 |
| `artifact-checks.json`、`artifact-length-audit.json` | 12份下游原件的源码、独立16项行为、JSON、篇幅检查与辅助计数；源码未修补 |
| `history-checks.json`、`history-checks-candidate2.json` | 14条所有者历史的正文、版本、规则及内部记录不外露检查 |
| `review-mapping.json` | 两次评审的作品标签映射，未传给评审模型 |
| `user-view-reviews.json` | 首次汇总误拒空推荐数组的结果，保留不覆盖 |
| `user-view-review-audit.json` | 更正汇总工具后的离线结构、逐字引文及辅助评审局限，不修改评审原文 |
| `cost-observations.json`、`paired-cost-audit.json` | 含失败的完整批次分组，以及当前六组同信息对照和四组作品端到端Token／上游耗时 |
| `validation-summary.json`、`tool-verification.json` | 受影响129项Java最后报告去重合并、6项检查工具验证，非全仓库发布结论 |
| `post-evaluation-mock-validation.json` | 闭批后Mock换行兼容补修，新增8项、定向复核43项；最终137项Java去重合并，真实Provider及指导未改，新增实际调用0 |
| `environment-after.json` | 自建9002清理及原日常服务PID保留；未应用迁移／进行浏览器验收 |

`NaturalCoverageWorkflowEvaluationServer.java`和`NaturalCoverageWorkflowCorrectionServer.java`只在临时9002启动本地源码，接入真实模型和既有合成账号，并按阶段与总上限在每次实际传输前记账。凭据／DPAPI文件、Cookie、实际Key、私有历史及编译文件仅留忽略目录，不提交。模型端点与公开别名属于公开路由说明，别名不证明权重固定。

`start-*`、`invoke-*`、`run-*`是对应冻结阶段的原脚本，本目录不是新的调用授权。已存在闭批记录时禁止重新发模型请求。`build-summary*.mjs`会独占创建闭批及首次结果，不能把它当成可反复执行的更新脚本。

首阶段冻结的`run-evaluation.mjs`末尾空行按原件保留，未为格式检查改变哈希；提交差异检查仅忽略末尾空行，其他空白错误照常检查。

闭批后可从仓库根目录运行以下纯离线命令；已有输出相同时复用，不同则拒绝覆盖，不登录、不调用模型：

```powershell
node docs/testing/evidence/natural-coverage-workflow-2026-10-20/audit-final-results.mjs
```

`check-artifacts*.py`会编译并运行原始生成的Java，需先审查原件安全性；本轮原件已审查，仅使用BigDecimal且无I/O。不能将任意未知生成代码直接执行，也不能用本地安全审查替代未来部署与隔离验收。
