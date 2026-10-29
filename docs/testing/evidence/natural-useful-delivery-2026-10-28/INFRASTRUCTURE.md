# 本轮真实评测基础设施

本轮为2026-10-28用户新授权的最多500次实际调用，独立从0累计；旧182/200及所有旧闭批不改动、不恢复。阶段先冻结，逐个提交一次，原件及失败全部保留。准备、正文、Plan、执行、协议修复和付费评审每次上游尝试均计数；网络前先占用预算，中断预留同样占用。预算守门不证明提示词质量。

新后端为127.0.0.1:9014，只在临时类路径装入评测入口，使用当前产品服务、安全、历史和真实模型调用，不执行Flyway、不创建身份。日常9000初查未运行；PostgreSQL已运行且V46已应用。本轮恢复原Redis独立程序的原配置和数据目录，未注册新服务。非秘密环境核对见environment-before.json。

## 冻结和启动

`node freeze-live.mjs <stage> <bundle仓库相对路径>`。bundle沿用`syntheticOnly/cases/jobs`，另须`phaseLimits`显式列出GENERATION、PLAN、EXECUTION、REVIEW的本阶段调用上限（未列阶段为0）。job包含id、caseId、phase、version；增强/Plan用实际捕获的approvedEnhancementInput，作品/评审用完整approvedModelRequest。允许31、45、46、47及作品的ORIGINAL；47仅在此前46阶段全部关闭后加入入口兼容，不回改旧实验。所有阶段总消耗受500硬上限保护。纯采集可用`captureOnly: true`、空jobs、全部phaseLimits为0，只准备确定性上下文，禁止模型调用。

`start-live.ps1 -Stage <stage> -SkipCompile`使用已编译产品类，复制成隔离运行副本，再冻结运行类和脚本指纹。若需编译，先与其他开发协调共享target。新runtime目录已存在时拒绝复用，9014被占用时拒绝启动。启动本身不发送模型请求。

`invoke-live.ps1 -Stage <stage> -Job LOGIN`从既有`tmp/local-acceptance/account.json`和DPAPI凭据正常登录，验证码图片和Cookie仅存私有tmp。会话失效需重新正常登录，不能创建替代账号。`COUNT`只读计数，其他job严格按冻结一次执行。温度0.2、官方DeepSeek端点、deepseek-flash、thinking disabled及stream false由传输层核对。

## 浏览器与普通默认入口

浏览器从工作台真正选择目录、索引检索、构造POST；与冻结DTO逐项相等后才设置active-job.json并附`X-Evaluation-Job`头。本阶段一次只执行一个业务作业，active-job不是并发作业队列。私有浏览器state位置为`tmp/natural-useful-delivery-2026-10-28/<stage>/auth/browser-storage-state.json`。

对照增强路由`/api/v2/evaluation/candidate`，Plan路由`/api/v2/evaluation/plan-baseline`。后续默认采用后，job加`entrypoint: "DEFAULT"`，通过原`/api/v2/optimizations`或`/plan`入口验证，不由临时接口切换版本；job的version必须为当时冻结的预期默认版本。

## 闭批

`node close-stage.mjs <stage> <预期实际调用数>`逐字复制安全JSON原件和业务结果，排除认证、Cookie和私有服务器日志，结算未用预留并锁定阶段。阶段关闭后不能重开或重复调用。返回200、字段齐全及闭批成功均不代表作品质量通过；质量采用结论由主报告另行给出。

离线基础设施检查见infrastructure-verification.json，检查只使用隔离计数夹具，未发送模型请求。
# Future writer-thinking mechanism profile

`WRITER_THINKING_ABLATION_V1` applies only to one newly frozen six-call stage. Three actual candidate48 first-writer requests captured by the product HTTP test each receive one OFF and one ON replay. Their original files and SHA256 are required; freeze copies immutable source snapshots. The two arms retain complete system/user messages, JSON mode, model and all other fields. Both use 32768 output tokens; OFF has disabled thinking with no effort override, ON has enabled thinking and effort high. These are isolated writer mechanism calls under EXECUTION accounting, not downstream works or adoption evidence. Scope, Plan and actual executors are unchanged.

The temporary profile uses a 300-second model read timeout and 360-second runner deadline. Each job is single-pass, with no protocol or quality retry; an unresolved reservation stops the profile. Length, timeout or empty content remains an incomplete-delivery record and cannot be scored as a completed semantic comparison. The transport still reserves before upstream and enforces the shared 500-call ceiling; hidden reasoning text remains removed while token statistics are retained.

Unprofiled stages keep disabled thinking, no effort override and their previously frozen token caps. Unknown profiles, extra stages repeating this profile, request drift, changed source hashes, extra calls, mixed arms and generation/Plan/downstream routes are rejected. All old closed stage files and the task authorization remain unchanged. Profile preparation does not authorize freeze or execution before root review of the complete bundle.

Offline checks: `node writer-thinking-profile-selftest.mjs` (isolated temporary fixtures), and `WriterThinkingProfileSelfTest` compiled against a frozen runtime. Neither starts Spring or calls a model.

