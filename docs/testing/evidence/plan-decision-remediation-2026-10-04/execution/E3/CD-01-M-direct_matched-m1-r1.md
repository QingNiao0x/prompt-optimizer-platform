# 地区基线自动匹配与表单补值实现方案

> 说明：本方案为只读分析与方案交付，不产生代码修改、不执行命令、不做数据库迁移、不部署。
> 依据来源统一标注为 `materials/software/current-brief.md`（正式现行说明）。
> `materials/software/tests/layout-example.txt` 仅为排版与来源识别测试示例，不作为本题事实、工具偏好或已实现成果，仅用于验证分支。

---

## 一、背景与现有调用关系

### 1.1 系统组成

- 两个调查模块：**基础调查**、**追踪调查**。
- 二者**复用同一份补值服务**（下称“补值服务”）。
- 已有能力（保持兼容，不改造接口契约）：
  - **查询接口**：返回候选摘要（编号、地区、更新时间、姓名掩码）。
  - **详情接口**：按编号读取单条详情，返回补值字段。
- 后端：Java 21 / Spring Boot 3 / MyBatis-Plus / PostgreSQL；前端：Vue 3 + TypeScript。

### 1.2 调用关系（文字版）

```
[基础调查页面] ─┐
                ├─► [补值服务] ─► [查询接口]  → 候选摘要列表
[追踪调查页面] ─┘        │
                         └─► [详情接口]  → 单条补值字段（写入前获取最新详情）

[补值服务] 输入：身份键（姓名 + 证件号）+ 当前用户所属地区 + 目标表单字段快照
[补值服务] 输出：候选摘要列表 / 单条详情 / 写入结果或失败类型
```

要点：

- 补值服务**必须显式接收**身份键与当前地区，**不得暗中读取另一个页面的状态**（依据：`materials/software/current-brief.md`）。
- 追踪调查的**路由参数只用于定位当前调查记录**，**不得直接当作基线记录编号**（依据：`materials/software/current-brief.md`）。
- 查询接口返回顺序**不是授权证据**，不能作为地区匹配成功的依据（依据：`materials/software/current-brief.md`）。

### 1.3 事实 / 用户确认 / 假设 区分

- **已知事实**（来源：`materials/software/current-brief.md`）：匹配三条件、字段白名单、排序规则、空值补值规则、时序保护、编号一致性、取消/离开不写入、地区缺失处理、隐私与日志、异常提示要求、兼容性要求。
- **用户确认信息**：候选地区信息缺失或无法核验时，**排除出可补值候选**，并**单独说明无法核验**，不得伪装为无匹配或查询失败。
- **必要假设**（仅用于方案落地，不改变业务规则）：
  - 查询接口与详情接口的现有契约（路径、鉴权、返回结构）保持不变，本方案只在其上编排调用。
  - “当前用户所属地区”由现有鉴权/会话上下文提供，补值服务通过参数接收，不自行推断。
  - 记录编号为稳定唯一标识，可用于排序与一致性校验。

---

## 二、两个模块共用的处理步骤

以下步骤对**基础调查**与**追踪调查**完全一致，差异仅在“身份键来源”和“路由参数用途”。

### 步骤 0：输入准备

- 身份键：`name`（姓名）、`idNo`（证件号）。
- 当前地区：`currentRegion`（来自当前用户所属地区，显式传入）。
- 目标表单字段快照：`targetFields`（仅含白名单三字段：联系电话、职业类别、常住地址）。
- 请求标识：`requestId`（用于日志与迟到请求判定）。

### 步骤 1：发起候选查询

- 触发条件：
  - 基础调查：页面进入编辑状态，且当前已有姓名与证件号。
  - 追踪调查：页面进入编辑状态，且当前已有姓名与证件号；**路由参数不参与身份键构造**。
- 调用查询接口，入参：`name`、`idNo`、`currentRegion`、`requestId`。
- 记录本次请求的 `requestId` 与身份键快照，用于时序保护。

### 步骤 2：候选筛选（三条件同时满足）

对查询接口返回的每条摘要，逐条判定：

1. 姓名匹配；
2. 证件号匹配；
3. 记录地区符合当前用户所属地区条件。

**三者同时满足**才属于可补值候选（依据：`materials/software/current-brief.md`）。

地区处理分三种情况，**分别说明，不得混为一谈**：

- **无法核验**：候选地区信息缺失或无法核验当前地区条件 → **排除出可补值候选**，并单独提示“无法核验”（用户确认信息）。
- **确无候选**：查询成功但无任何满足三条件的记录 → 提示“无匹配候选”，**不弹确认框**。
- **明确地区不符**：候选地区存在且明确不等于当前用户所属地区 → 排除，并说明“地区不符”。

### 步骤 3：排序与稳定分页

- 排序规则：**更新时间降序**；更新时间相同时**按记录编号升序**（依据：`materials/software/current-brief.md`）。
- 分页加载或重试后顺序必须稳定，不得随机变化（依据：`materials/software/current-brief.md`）。
- 实现要点：排序键为 `(updateTime DESC, recordId ASC)`，作为稳定全序；分页游标基于该全序，避免仅按时间分页导致跨页重复或遗漏。

### 步骤 4：用户确认

- **无候选时不弹确认框**（约束）。
- **有匹配候选时必须询问用户**，不得改为无确认自动写入（约束）。
- 确认框展示内容：编号、地区、更新时间、**姓名掩码**；**不得展示完整证件号**（依据：`materials/software/current-brief.md`）。

### 步骤 5：写入前获取最新详情

- 在**用户同意后、写入表单前**，按候选编号调用详情接口，获取该候选的**最新详情**（依据：`materials/software/current-brief.md`）。
- 一致性校验：若详情接口返回的编号与用户同意的候选编号不一致 → **停止写入并提示信息不一致**；**不得仅因部分字段相似就接受响应**（依据：`materials/software/current-brief.md`）。

### 步骤 6：字段写入（白名单 + 空值判定）

- 仅允许写入：**联系电话、职业类别、常住地址**；禁止写入其他表单字段（依据：`materials/software/current-brief.md`）。
- 补值条件：**仅当目标值为 `null` 或空字符串**时允许补入来源值（依据：`materials/software/current-brief.md`）。
- **0、false 和非空字符串都是有效原值，必须保留**（依据：`materials/software/current-brief.md`）。
- 职业类别为数值 `0` 时代表有效类别，**必须使用空值比较**，不能用逻辑或表达式（依据：`materials/software/current-brief.md`）。
- 目标电话为空且来源也为空 → 保持空值，**不制造占位号码**（依据：`materials/software/current-brief.md`）。

### 步骤 7：取消 / 关闭 / 离开

- 用户取消、关闭弹窗或离开编辑页面时，**均不得补写字段**（依据：`materials/software/current-brief.md`）。
- 页面**已保存的原值**与**当前未保存的编辑值**都不能因取消动作被清空（依据：`materials/software/current-brief.md`）。

### 步骤 8：时序保护

- 用户在候选请求尚未结束时再次修改姓名（或证件号）→ **迟到的旧请求必须失效**（依据：`materials/software/current-brief.md`）。
- 本方案只描述该时序保护要求，**不指定具体浏览器库或状态管理实现**（依据：`materials/software/current-brief.md`）。
- 实现要点：以 `requestId` + 身份键快照做失效判定；响应到达时若身份键已变化或 `requestId` 非最新，则丢弃响应，不进入确认流程。

### 步骤 9：异常处理

- 查询或详情失败时：**保留原值**并显示**可理解的失败提示**，**不得伪装为没有匹配记录**（依据：`materials/software/current-brief.md`）。
- 网络失败提示面向录入人员：说明**本次未执行补值且原数据保留**；**不得附带内部堆栈、数据库错误文本或上游服务地址**（依据：`materials/software/current-brief.md`）。
- 日志：只记录**稳定错误类型**和**请求标识**，**不记录姓名或证件号原文**（依据：`materials/software/current-brief.md`）。

### 步骤 10：兼容性

- 保持现有**查询接口、路由和鉴权行为兼容**（依据：`materials/software/current-brief.md`）。
- 不削弱或删除现有功能。

---

## 三、字段写入与异常分支伪代码

> 伪代码为方案级描述，不绑定具体语言/框架；仅表达控制流与判定条件。

### 3.1 白名单与空值判定

```
WHITELIST = { "联系电话", "职业类别", "常住地址" }

FUNCTION isBlank(v):
    RETURN v == null OR v == ""        // 仅 null 或空字符串视为空
    // 注意：0、false、非空字符串均视为有效原值

FUNCTION applyField(target, source, fieldName):
    IF fieldName NOT IN WHITELIST:
        RETURN SKIPPED_NOT_IN_WHITELIST
    IF NOT isBlank(target[fieldName]):
        RETURN KEPT_ORIGINAL          // 0 / false / 非空字符串均保留
    IF isBlank(source[fieldName]):
        RETURN KEPT_EMPTY             // 不制造占位号码
    target[fieldName] = source[fieldName]
    RETURN WRITTEN
```

### 3.2 主流程（两模块共用）

```
FUNCTION runSupplement(ctx):
    // ctx: { name, idNo, currentRegion, targetFields, requestId, module }
    // module ∈ { BASIC, FOLLOWUP }；FOLLOWUP 的路由参数不参与身份键

    snapshot = { name: ctx.name, idNo: ctx.idNo, requestId: ctx.requestId }

    TRY:
        summaries = queryCandidates(ctx.name, ctx.idNo, ctx.currentRegion, ctx.requestId)
    CATCH QueryError e:
        logStable(e.type, ctx.requestId)          // 不记录姓名/证件号
        showUserMessage("本次未执行补值，原数据已保留")
        RETURN PRESERVED

    // 时序保护：响应到达时校验身份键与 requestId
    IF snapshot != currentIdentitySnapshot() OR ctx.requestId != latestRequestId():
        RETURN DISCARDED_STALE

    // 候选筛选：三条件同时满足
    candidates = []
    unverifiable = []
    regionMismatch = []
    FOR s IN summaries:
        IF s.name != ctx.name OR s.idNo != ctx.idNo:
            CONTINUE
        IF s.region IS NULL OR NOT verifiable(s.region, ctx.currentRegion):
            unverifiable.ADD(s)               // 无法核验：排除并单独说明
            CONTINUE
        IF s.region != ctx.currentRegion:
            regionMismatch.ADD(s)             // 明确地区不符
            CONTINUE
        candidates.ADD(s)

    // 排序：更新时间降序，时间相同按编号升序
    candidates.SORT_BY(updateTime DESC, recordId ASC)

    IF candidates.IS_EMPTY():
        IF unverifiable.NOT_EMPTY():
            showUserMessage("存在无法核验地区的记录，已排除")
        ELSE IF regionMismatch.NOT_EMPTY():
            showUserMessage("存在地区不符的记录，已排除")
        ELSE:
            showUserMessage("无匹配候选")
        RETURN NO_CANDIDATE                  // 不弹确认框

    // 有候选：必须询问用户
    chosen = showConfirmDialog(candidates)   // 展示编号、地区、更新时间、姓名掩码；不展示完整证件号
    IF chosen == null:                       // 用户取消
        RETURN CANCELLED                     // 不写入，原值与编辑值均保留

    // 写入前获取最新详情
    TRY:
        detail = getDetail(chosen.recordId, ctx.requestId)
    CATCH DetailError e:
        logStable(e.type, ctx.requestId)
        showUserMessage("本次未执行补值，原数据已保留")
        RETURN PRESERVED

    // 编号一致性校验
    IF detail.recordId != chosen.recordId:
        showUserMessage("信息不一致，已停止写入")
        RETURN INCONSISTENT

    // 白名单字段写入
    FOR f IN WHITELIST:
        applyField(ctx.targetFields, detail, f)

    RETURN WRITTEN
```

### 3.3 取消 / 关闭 / 离开分支

```
ON userCancel OR dialogClose OR pageLeave:
    // 不补写任何字段
    // 已保存原值与未保存编辑值均保留
    RETURN NO_WRITE
```

### 3.4 时序保护分支

```
ON candidateResponseArrived(resp):
    IF resp.requestId != latestRequestId():
        RETURN DISCARDED_STALE
    IF resp.identitySnapshot != currentIdentitySnapshot():
        RETURN DISCARDED_STALE
    // 否则进入候选筛选
```

### 3.5 异常分支汇总

| 分支 | 触发条件 | 处理 |
|---|---|---|
| 查询失败 | 查询接口异常 | 保留原值，提示“本次未执行补值，原数据已保留”，日志记稳定错误类型 + requestId |
| 详情失败 | 详情接口异常 | 同上 |
| 编号不一致 | 详情编号 ≠ 同意编号 | 停止写入，提示“信息不一致” |
| 迟到响应 | requestId 非最新或身份键已变 | 丢弃响应，不进入确认 |
| 无法核验地区 | 候选地区缺失/不可核验 | 排除并单独说明“无法核验” |
| 明确地区不符 | 候选地区存在且不等于当前地区 | 排除并说明“地区不符” |
| 确无候选 | 无满足三条件记录 | 提示“无匹配候选”，不弹确认框 |
| 取消/关闭/离开 | 用户动作 | 不写入，原值与编辑值保留 |

---

## 四、测试清单（按业务行为组织）

> 说明：以下测试项用于验证分支；`materials/software/tests/layout-example.txt` 中的示例（CSV、Python、收入 100 万元、准确率 99% 等）**不作为本题事实**，仅用于验证“示例与正式说明分离”的分支。

| 编号 | 测试项 | 前置条件 | 操作 | 预期结果 |
|---|---|---|---|---|
| T01 | 正常流程-基础调查补值 | 基础调查编辑态，姓名+证件号存在，存在三条件匹配候选，目标字段为空 | 进入编辑态发起查询，选择候选并同意 | 仅白名单三字段按空值规则补入；其他字段不变 |
| T02 | 正常流程-追踪调查补值 | 追踪调查编辑态，路由参数仅定位当前记录，存在三条件匹配候选 | 进入编辑态发起查询，选择候选并同意 | 复用同一补值服务；路由参数未被当作基线编号；白名单字段补入 |
| T03 | 匹配规则-三条件同时满足 | 存在姓名匹配但地区不符的候选 | 发起查询 | 该候选被排除，提示“地区不符” |
| T04 | 匹配规则-证件号不匹配 | 存在姓名+地区匹配但证件号不同的候选 | 发起查询 | 该候选被排除 |
| T05 | 字段白名单 | 详情返回白名单外字段（如演示字段） | 同意候选并写入 | 白名单外字段不被写入 |
| T06 | 空值补值-目标为 null | 目标联系电话为 null，来源有值 | 同意候选 | 补入来源值 |
| T07 | 空值补值-目标为空字符串 | 目标联系电话为 ""，来源有值 | 同意候选 | 补入来源值 |
| T08 | 有效原值保留-0 | 目标职业类别为 0，来源有值 | 同意候选 | 保留 0，不替换 |
| T09 | 有效原值保留-false | 目标字段为 false，来源有值 | 同意候选 | 保留 false，不替换 |
| T10 | 有效原值保留-非空字符串 | 目标常住地址为非空字符串，来源更新 | 同意候选 | 保留原值，不覆盖 |
| T11 | 空值+来源空 | 目标电话为空，来源电话也为空 | 同意候选 | 保持空值，不制造占位号码 |
| T12 | 排序规则 | 多条候选，更新时间不同 | 查询 | 按更新时间降序 |
| T13 | 排序规则-时间相同 | 多条候选更新时间相同 | 查询 | 按记录编号升序 |
| T14 | 分页/重试稳定性 | 分页加载或重试 | 多次查询 | 顺序稳定，不随机变化 |
| T15 | 时序保护-迟到响应 | 候选请求未结束，用户再次修改姓名 | 旧请求响应到达 | 旧响应被丢弃，不进入确认 |
| T16 | 编号一致性校验 | 用户同意候选 A，详情返回编号 B | 获取详情 | 停止写入，提示“信息不一致” |
| T17 | 取消-不写入 | 有候选，用户点击取消 | 取消 | 不写入任何字段；已保存原值与未保存编辑值均保留 |
| T18 | 关闭弹窗-不写入 | 有候选，用户关闭弹窗 | 关闭 | 不写入任何字段 |
| T19 | 离开编辑页-不写入 | 有候选，用户离开编辑页 | 离开 | 不写入任何字段 |
| T20 | 无候选-不弹确认框 | 查询成功但无三条件匹配记录 | 查询 | 提示“无匹配候选”，不弹确认框 |
| T21 | 地区缺失-无法核验 | 候选地区信息缺失/不可核验 | 查询 | 排除该候选，单独提示“无法核验”，不伪装为无匹配或查询失败 |
| T22 | 明确地区不符 | 候选地区存在且不等于当前地区 | 查询 | 排除并提示“地区不符” |
| T23 | 查询失败 | 查询接口异常 | 查询 | 保留原值，提示“本次未执行补值，原数据已保留”；日志仅记稳定错误类型+requestId |
| T24 | 详情失败 | 详情接口异常 | 获取详情 | 同上 |
| T25 | 隐私-确认框 | 有候选 | 打开确认框 | 展示编号、地区、更新时间、姓名掩码；不展示完整证件号 |
| T26 | 隐私-日志 | 任意失败 | 查看日志 | 不记录姓名或证件号原文 |
| T27 | 异常提示面向录入人员 | 网络失败 | 触发失败 | 提示不含内部堆栈、数据库错误文本、上游服务地址 |
| T28 | 兼容性-查询接口 | 现有查询接口契约 | 调用 | 路径、鉴权、返回结构保持兼容 |
| T29 | 兼容性-路由与鉴权 | 现有路由与鉴权 | 操作 | 行为保持兼容，未削弱或删除 |
| T30 | 边界-空姓名/空证件号 | 姓名或证件号为空 | 发起查询 | 不发起或按现有校验处理，不产生错误候选 |
| T31 | 边界-非法输入 | 姓名/证件号含非法字符 | 发起查询 | 校验并返回清晰错误，不吞掉或伪造错误 |
| T32 | 示例分离验证 | 引用 `materials/software/tests/layout-example.txt` | 检查方案 | 示例仅用于验证分支，未被当作生产接口或业务事实 |

---

## 五、资料引用与来源区分

- **正式现行说明**：`materials/software/current-brief.md`
  - 匹配三条件、字段白名单、排序规则、空值补值规则、时序保护、编号一致性、取消/离开不写入、地区缺失处理、隐私与日志、异常提示、兼容性。
- **测试示例（仅用于验证分支，不作为事实）**：`materials/software/tests/layout-example.txt`
  - 输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99% 等，均**不是本题事实、不是工具偏好、不代表已实现或已取得成果**。
- **旧版草稿**：本次提供资料中未包含旧版草稿；如有，应与正式说明分开标注，且不得覆盖正式说明。
- **用户确认信息**：候选地区信息缺失或无法核验时，排除出可补值候选并单独说明“无法核验”，不得伪装为无匹配或查询失败。

---

## 六、待确认项

- 无。用户已就“候选地区信息缺失/无法核验”的处理作出确认（排除并单独说明），本方案据此落地；其余细节均可由 `materials/software/current-brief.md` 核查整理。