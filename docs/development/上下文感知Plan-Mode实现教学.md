# 上下文感知 Plan Mode 实现教学

## 1. 要解决的问题

旧流程一律先根据用户输入的需求文本提问，等用户回答后才读取文件。这样既会强制不需要澄清的用户进入 Plan，也会出现两类重复问题：项目文件已经明确使用 Spring Boot，系统仍询问技术环境；研究数据已经是 Excel，系统仍询问数据格式。

当前实现把 Plan 改为用户主动开启，首次访问默认关闭并直接增强。用户开启 Plan 后采用方案 B：有文件时先分析上下文，再生成确认问题。这里的“先分析”不是把整个目录直接发给模型，而是先在浏览器或临时文档索引中选出初步相关内容，由后端过滤、解析和摘要，再把安全摘要交给计划模型。

关闭 Plan 时，无论是否有上传资料，都不会调用上下文准备接口和计划接口；有文件时按原始需求检索一次，用户同意发送后由最终接口完成分析。开启 Plan 但没有上传资料时，流程保持轻量：直接根据需求和背景生成确认问题，不调用上下文准备接口。

## 2. 上下文感知处理流程

### 2.1 决策流程图

```mermaid
flowchart TD
    A([用户输入需求，可选文件或目录]) --> B{用户是否开启 Plan 确认？}
    B -- 否 --> B1[按原始需求检索一次最终文件]
    B1 --> B2{存在文件且需要发送确认？}
    B2 -- 否 --> B4[调用最终接口<br/>AUTO + planConfirmation=null]
    B2 -- 是 --> B3{用户同意发送？}
    B3 -- 否 --> X([取消本次增强])
    B3 -- 是 --> B4
    B4 --> Y[生成最终结构化提示词<br/>允许返回 ambiguities]

    B -- 是 --> C0{存在手动文件或<br/>READY 状态的本地索引？}
    C0 -- 否 --> C[按需求文本和背景创建 Plan]
    C0 -- 是 --> D[按原始需求和项目概览词<br/>第一次检索相关文件]
    D --> E{用户同意发送<br/>初步上下文？}
    E -- 否 --> X([取消本次增强])
    E -- 是 --> F[后端过滤受保护路径<br/>解析文件并生成安全摘要]
    F --> G[保存 30 分钟上下文会话<br/>返回 contextId 和 version]
    G --> H[按需求文本和上下文摘要创建 Plan]

    C --> I[生成领域化问题和候选答案]
    H --> I
    I --> J{存在需要用户决定的<br/>关键问题？}
    J -- 是 --> K[Plan Mode 弹窗逐题回答]
    J -- 否 --> L[生成绑定当前 Plan 的空确认]
    K --> M[提交 planId、上下文引用和全部答案]
    L --> M

    M --> N[组合原始需求、服务端问题文本和答案<br/>形成最终检索词]
    N --> O{本次增强包含文件上下文？}
    O -- 否 --> P[提交无文件的最终增强请求]
    O -- 是 --> Q[按最终检索词第二次检索相关文件]
    Q --> R{用户同意发送<br/>最终上下文？}
    R -- 否 --> X
    R -- 是 --> S[提交最终文件上下文和计划确认]

    P --> T[后端校验 Plan、问题、答案和上下文绑定]
    S --> T
    T --> U{查询、文件集合和<br/>上下文版本完全相同？}
    U -- 是 --> V[复用首次 ContextSnapshot]
    U -- 否 --> W[按最终检索词重新分析上下文]
    V --> Y[生成最终结构化提示词]
    W --> Y
    Y --> Z([展示、复制、编辑、撤销或再次增强])
```

直接增强只检索一次。Plan 路径中的第一次检索服务于提问，目标是让系统避免重复询问文件中已经明确的信息；第二次检索服务于最终生成，必须纳入用户刚刚确认的答案。只有查询、文件集合和上下文版本都没有变化时，后端才会复用首次分析快照。

### 2.2 完整执行时序

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户
    participant Page as PromptWorkbenchPage
    participant Store as optimization Store
    participant Local as 浏览器本地索引
    participant ContextApi as /context/planning
    participant PlanApi as /optimizations/plan
    participant Dialog as PlanQuestionDialog
    participant FinalApi as /optimizations

    User->>Page: 选择文件或文件夹
    Page->>Store: setFiles / setProjectIndex
    User->>Page: 输入需求并点击当前增强按钮
    alt Plan 关闭
        Page->>Store: prepareContextFiles(原始需求)
        Store->>Local: 检索一次最终相关文件
        Local-->>Store: 最终相关文件
        Page->>User: 有文件时确认发送范围
        User-->>Page: 同意
        Page->>Store: runOptimization
        Store->>FinalApi: templateCode=AUTO + planConfirmation=null
        FinalApi-->>Page: optimizedPrompt + sections + ambiguities
    else Plan 开启
        Page->>Store: prepareContextFiles(原始需求 + 项目概览词)
        Store->>Local: 第一次检索相关代码块
        Local-->>Store: 初步相关文件
        Page->>User: 确认本次上下文发送范围
        User-->>Page: 同意
        Page->>Store: preparePlanningContext
        Store->>ContextApi: rawPrompt + context + permissionPolicy
        ContextApi-->>Store: contextId/version + digest + contextReport
        Page->>Store: createOptimizationPlan
        Store->>PlanApi: rawPrompt + contextDescription + contextId/version
        PlanApi-->>Store: planId + questions + contextId/version
        Store-->>Dialog: 显示仍需用户决定的问题
        User->>Dialog: 逐项回答
        Dialog-->>Page: planId + contextId/version + answers
        Page->>Store: prepareContextFiles(原始需求 + 问题 + 答案)
        Store->>Local: 第二次检索
        Local-->>Store: 最终相关文件
        Page->>User: 确认最终上下文发送范围
        User-->>Page: 同意
        Page->>Store: runOptimization
        Store->>FinalApi: 最终上下文 + 计划确认
        FinalApi-->>Page: optimizedPrompt + sections + contextReport
    end
```

## 3. 前端代码如何串起来

入口位于 `apps/web/src/pages/PromptWorkbenchPage.vue`。

### 3.1 手动模式分流

`usePlanModePreference.ts` 用共享响应式状态和 `localStorage` 保存 `{ enabled, introSeen }`。默认值都是 `false`。首次主动开启后，`beginEnhancement()` 先显示用途说明；接受后进入 Plan，拒绝后关闭开关并立即直接增强。

`handleOptimize()` 和 `handleReEnhance()` 都先固定本次 `pendingPrompt`，再由 `continueEnhancement()` 根据当前开关分流：

```ts
if (planModeEnabled.value) {
  await runPlannedEnhancement();
  return;
}
await runDirectEnhancement();
```

因此“再次增强”也遵循当时的开关，而不是固定进入 Plan。生成期间开关被禁用，避免一次请求中途改变模式。

直接增强调用 `prepareContextTransmission(pendingPrompt, '直接增强提示词', true)`，然后执行 `runOptimization()`。Store 会清除旧的 `plan/planningContext`，将模板重置为 `AUTO`，并发送 `planConfirmation: null`，防止复用上一次 Plan 或历史记录中的模板。

### 3.2 第一次上下文检索

`runPlannedEnhancement()` 先调用 `prepareContextForPlan()`，成功后才创建计划：

```ts
if (!await prepareContextForPlan(pendingPrompt.value)) {
  return;
}
if (!await store.createOptimizationPlan(pendingPrompt.value)) {
  return;
}
```

`prepareContextForPlan()` 只在手动文件不为空或本地项目索引为 `READY` 时工作。索引仍在扫描、暂停或失败时，不会悄悄退回纯文本计划，而是提示用户先完成索引。

它调用 `prepareContextTransmission()` 完成三件事：

1. 通过 Store 检索当前任务相关文件；
2. 展示文件数、内联字符数和大型文档引用大小；
3. 按隐私设置等待用户确认发送。

第一次发送的目的文案明确为：后端先做安全分析，计划模型只看到裁剪摘要。

### 3.3 保存上下文引用

`apps/web/src/stores/optimization.ts` 中的 `preparePlanningContext()` 调用：

```text
POST /api/v1/context/planning
```

响应同时保存到两个状态：

- `planningContext`：保存 `contextId`、`version`、安全摘要和过期时间，供计划请求引用；
- `contextSnapshot`：保存可展示的分析报告，供工作台立即呈现技术栈、文件摘要和警告。

添加、替换、删除或清空文件，以及替换项目索引时，Store 会清除旧的 `planningContext`，避免下一次增强误用旧引用。

### 3.4 计划请求不重复发送文件

`buildOptimizationPlanRequest()` 只组装：

```ts
{
  rawPrompt,
  contextDescription,
  conversationHistory: [],
  planningContext: { contextId, version }
}
```

文件正文不会出现在 `/optimizations/plan` 请求中。后端根据引用取得已经分析过的安全摘要，再把摘要放进 `PlanningProviderRequest`。

### 3.5 第二次检索为什么必须包含答案

用户回答后，`buildRefinedContextQuery()` 按以下顺序拼接查询：

```ts
[
  rawPrompt,
  `${question1}\n${answer1}`,
  `${question2}\n${answer2}`,
].join('\n');
```

例如用户原始需求是“分析某地区心脑血管疾病死亡率”，确认地区为“广东省”、工具为“R”，第二次查询会同时包含这些事实。本地索引因此可以召回广东省数据字典、R 脚本或相关方法文件，而不是继续只按“某地区”检索。

`PlanQuestionDialog.vue` 回传 `planId`、`planningContext` 和全部答案。最终请求不能只传答案，否则服务端无法证明这些答案来自哪一次计划。

## 4. 后端代码如何串起来

### 4.1 上下文准备

`PlanningContextController` 把请求交给 `PlanningSessionService.prepareContext()`。应用服务依次执行：

```text
校验需求和描述中是否疑似包含真实凭据
→ ProtectedContextFilter 移除平台及用户声明的受保护路径
→ ContextAnalyzer 按原始需求分析或检索文件
→ 生成不含文件正文的 PlanningContextDigest
→ 计算 context version
→ 保存 30 分钟短期 ContextSession
```

`PlanningContextDigest` 只包含以下信息：

- 用户描述；
- 技术栈名称；
- 依赖名称与版本；
- 受限数量的目录节点；
- 文件路径与独立摘要；
- 分析状态、成功文件数和非敏感警告。

完整的脱敏 `ContextSnapshot` 留在短期会话中，并返回给工作台展示。计划 Provider 不接收 `fileSnippets[].content`。

### 4.2 上下文版本

`version` 是 SHA-256 指纹，输入包含：

```text
分析查询
项目描述
按路径排序后的文件路径
语言
documentId
文件大小
内联内容
```

文件顺序变化不会造成误判，文件内容、文档引用、描述或分析查询变化会得到新版本。版本只用于一致性比较，不能代替访问控制。

### 4.3 生成并注册计划

`OptimizationPlanningService.plan()` 先通过 `resolveForPlan()` 验证上下文引用属于同一份需求和项目描述，再调用 `PromptPlanningProvider`。Provider 返回的问题还会经过数量、长度、ID、候选答案、内部术语和凭据检查。

验证完成后，服务端生成 `planId` 并保存：

```text
需求 + 项目描述 + 会话历史的指纹
上下文引用
服务端实际展示的问题集合
过期时间
```

计划模型只负责提出问题。它不能生成 `planId`，也不能决定会话绑定规则。

### 4.4 校验最终确认

`DefaultEnhancementOrchestrator.optimize()` 首先调用 `PlanningSessionService.confirm()`。有 `planId` 的标准请求必须满足：

1. 计划仍在 30 分钟有效期内；
2. 需求、项目描述和会话历史未被替换；
3. `planningContext` 与生成问题时使用的引用一致；
4. 回答 ID 集合与服务端问题 ID 集合完全相同；
5. 每个答案非空且问题 ID 不重复。

服务端忽略客户端回传的问题文案，使用短期会话中保存的原问题和客户端答案重新组成 `PlanAnswer`。这样客户端不能通过修改 `question` 字段改变答案的语义背景。

### 4.5 最终上下文分析与复用

编排器用规范化后的问题和答案构造最终分析查询。随后比较最终输入与首次 `version`：

- 查询和文件完全一致：复用首次 `ContextSnapshot`；常见于计划没有问题的直接生成；
- 存在确认答案：查询已经变化，重新检索大型文档并分析；
- 本地索引选出了不同代码块：文件指纹变化，重新分析；
- 文件未变但内容改变：内容指纹变化，重新分析。

完成上下文处理后，原有链路继续执行：模板选择、工程约束补全、权限红线、Provider 生成、四要素校验、结果组装和历史保存。

## 5. Redis 与本地降级

`HybridPlanningSessionStore` 根据 `app.planning.store-mode` 工作。本地默认 `LOCAL_FALLBACK`：保留进程内副本，有 Redis 时也写入 Redis；多实例应设置 `REDIS_REQUIRED`：只以 Redis 为权威存储，读写失败返回 `503 PLANNING_STORE_UNAVAILABLE`，不能回退到其他实例无法读取的本地副本。

```text
prompt-optimizer:planning-context:v2:{contextId}
prompt-optimizer:plan:v2:{planId}
```

两类键的 TTL 都按会话 `expiresAt` 设置，默认 30 分钟。Redis 读写失败时日志只记录异常类型，不记录连接字符串、凭据或上游响应正文。

进程内降级仅适合单实例本地联调。生产多实例使用 `REDIS_REQUIRED`，同一个 `contextId`／`planId` 在任意实例读取相同 Redis 记录。登录用户所有权已由服务端 `CurrentActor.userId` 校验；不同登录用户无法读取对方的短期会话。计划有效期不超过其引用的上下文有效期。

生成问题时，摘要会先按原始需求与文件摘要的相关性排序，再留出部分位置给不同目录；仍限制最多 30 条并报告覆盖不足。Provider 问题通过格式校验后，会按明确字段事实保守过滤重复提问，规范化去除完全重复的文案，最终只保存并展示过滤后的问题。对冲突、复合问题或模糊表述保留提问。兼容 Provider 已有自己的结构修复上限；应用层只对 Provider 成功返回但应用层校验失败的结果额外重试一次，认证、限流和上游不可用不会按结构错误重试。

过期的标准请求返回 `409 PLANNING_SESSION_EXPIRED`。工作台重新请求上下文和问题，匹配完全相同的问题文案及选项后恢复草稿；有变化的旧问题答案单独展示供核对，新问题仍需用户再次确认。重新获取文件需重新经过原有发送确认。匿名草稿不会提交到旧计划。监控只记录问题数、过滤数、受控重试次数和交互事件枚举，不把正文作为指标标签。指标在本机进程中聚合；多实例需要统一采集 Micrometer 指标。

## 6. 两种模式的伪代码

### 默认直接增强

```text
finalFiles = localIndex.retrieve(rawPrompt)  // 有文件或 READY 索引时
userConfirms(finalFiles)                    // 有发送确认设置且文件非空时
result = POST /optimizations(
  rawPrompt,
  finalFiles,
  templateCode = AUTO,
  planConfirmation = null
)
```

### 开启 Plan（有文件或目录索引）

```text
initialFiles = localIndex.retrieve(rawPrompt + projectOverviewTerms)
userConfirms(initialFiles)
prepared = POST /context/planning(rawPrompt, initialFiles, permissionPolicy)

plan = POST /optimizations/plan(
  rawPrompt,
  description,
  planningContext = prepared.contextId/version
)

answers = dialog.collectAll(plan.questions)
refinedQuery = rawPrompt + plan.questions + answers
finalFiles = localIndex.retrieve(refinedQuery)
userConfirms(finalFiles)

result = POST /optimizations(
  rawPrompt,
  finalFiles,
  planConfirmation = plan.planId + plan.planningContext + answers
)
```

开启 Plan 但没有文件时，跳过 `initialFiles`、`/context/planning` 和两次文件发送确认，直接创建 Plan；最终请求仍必须携带 `planId` 和完整答案。

## 7. 错误与降级判断

| 情况 | 行为 |
| --- | --- |
| 本地项目索引尚未 `READY` | 前端停止流程并提示等待，不生成缺少上下文的计划 |
| 用户取消首次发送 | 不调用上下文准备和计划接口 |
| 上下文为空 | 后端返回明确的空分析状态，计划仍可根据文本继续 |
| `contextId/version` 过期 | 返回 409 与 `PLANNING_SESSION_EXPIRED`，重新分析上下文；版本不匹配仍返回 400 |
| `planId` 过期 | 返回 409 与 `PLANNING_SESSION_EXPIRED`，工作台重新生成问题并供用户核对旧答案 |
| 少答、多答或重复回答 | 返回 400，不进入 Provider |
| 语义检索失败 | 保留关键词检索或代表片段，并在 `contextReport.warnings` 中说明 |
| 最终检索与首次输入不同 | 重新分析，不复用旧快照 |
| Redis 不可用 | `LOCAL_FALLBACK` 可单实例降级；`REDIS_REQUIRED` 返回 503，不保存不可跨实例读取的计划 |
| Provider 失败 | 返回稳定错误码，弹窗保留用户回答以便重试 |

## 8. 如何验证实现

后端重点测试：

```powershell
cd services/api
mvn.cmd -q "-Dtest=PlanningSessionServiceTest,DefaultEnhancementOrchestratorTest,PlanningContextControllerTest" test
```

前端重点测试：

```powershell
cd apps/web
npm.cmd test -- --run src/features/optimization/optimizationRequest.test.ts src/stores/optimization.test.ts
npm.cmd run typecheck
```

浏览器测试中的关键断言是请求顺序：

```text
/context/planning
→ /optimizations/plan
→ /optimizations
```

同时检查计划请求只含 `contextId/version`，最终请求含 `planId`、同一个上下文引用和全部回答。对于带 `documentId` 的大型文档，还要验证最终后端分析查询中出现用户确认答案。

直接增强还应断言：不会调用 `/context/planning` 和 `/optimizations/plan`，最终请求使用 `templateCode=AUTO`、`planConfirmation=null`；首次开启说明可接受或拒绝，偏好在刷新后保留，再次增强跟随当前开关。

## 9. 后续演进边界

短期 ID 已绑定登录用户并在每次读取时校验。多租户团队上线前仍需按实际成员关系补充租户和工作区授权。生产环境还应限制同一用户并发会话数，并统一采集 Redis 命中率、过期错误率、首次与二次检索差异和 Plan 问题质量指标。
