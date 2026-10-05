# 地区基线自动匹配与表单补值实现方案

## 一、背景与现有调用关系

### 1.1 业务目标

在基础调查和追踪调查两个模块的编辑页面中，根据当前记录的姓名与证件号，自动查询符合当前用户所属地区条件的基线候选记录，经用户确认后，将候选记录中的联系电话、职业类别、常住地址三个字段补入当前表单。补值仅在目标字段为空时执行，不覆盖已有值。

### 1.2 现有接口与调用关系

| 接口 | 职责 | 返回内容 |
|------|------|----------|
| 候选查询接口 | 按姓名+证件号+当前地区查询候选列表 | 候选摘要：编号、地区、更新时间、姓名掩码 |
| 单条详情接口 | 按候选编号读取完整补值字段 | 联系电话、职业类别、常住地址等 |

调用时序：

```
编辑页面进入
  → 读取当前记录已有姓名、证件号
  → 发起候选查询（携带身份键 + 当前地区）
  → 返回候选摘要列表（按更新时间降序，同时间按编号升序）
  → 有候选：弹出确认框展示摘要（不含完整证件号）
  → 用户同意
  → 按候选编号获取最新详情
  → 校验详情编号与已同意编号一致
  → 按字段白名单和空值规则写入表单
  → 用户取消/关闭/离开：不写入，保留原值
```

### 1.3 两个模块的差异与共性

| 维度 | 基础调查 | 追踪调查 |
|------|----------|----------|
| 触发时机 | 进入编辑状态时 | 进入编辑状态时 |
| 身份键来源 | 当前表单已有姓名+证件号 | 当前表单已有姓名+证件号 |
| 路由参数用途 | 定位当前调查记录 | 定位当前调查记录，**不得**当作基线记录编号 |
| 地区条件 | 当前用户所属地区 | 当前用户所属地区 |
| 补值服务 | 共用 | 共用 |

共用服务必须接收明确的身份键（姓名、证件号）与当前地区作为入参，不得从页面状态、路由参数或全局变量中隐式读取。

### 1.4 字段白名单

| 字段 | 类型 | 补值规则 |
|------|------|----------|
| 联系电话 | 字符串 | 目标为 null 或空字符串时可补入；已有值不覆盖 |
| 职业类别 | 数值 | 目标为 null 或空字符串时可补入；数值 0 是有效原值，不触发替换 |
| 常住地址 | 字符串 | 目标为 null 或空字符串时可补入；仅此一个地址字段 |

禁止写入其他表单字段。附件中展示的户籍地址、单位地址、紧急联系人地址等仅作背景，不纳入白名单。

---

## 二、两个模块共用的处理步骤

### 2.1 主流程

```
步骤 1：进入编辑状态
  → 读取当前表单的姓名、证件号
  → 若任一为空，不发起查询

步骤 2：发起候选查询
  → 入参：姓名、证件号、当前用户所属地区
  → 记录本次请求标识（requestId）
  → 记录当前身份键快照（nameSnapshot, idSnapshot）

步骤 3：接收候选结果
  → 若请求标识已过期（用户已修改姓名/证件号），丢弃结果
  → 若查询失败，提示"本次未执行补值，原数据保留"，不伪装为无匹配
  → 若无候选，不弹确认框，流程结束
  → 若有候选，按更新时间降序、同时间按编号升序排列

步骤 4：展示确认框
  → 展示候选摘要：编号、地区、更新时间、姓名掩码
  → 不展示完整证件号
  → 用户可选择某条候选或取消

步骤 5：用户同意后获取详情
  → 按候选编号调用详情接口
  → 校验返回详情的编号与已同意编号一致
  → 不一致：停止写入，提示信息不一致

步骤 6：执行补值
  → 仅对白名单字段（联系电话、职业类别、常住地址）
  → 仅当目标值为 null 或空字符串时写入
  → 0、false、非空字符串均视为有效原值，保留

步骤 7：完成或取消
  → 写入成功：更新表单字段
  → 用户取消/关闭/离开：不写入，保留全部原值和未保存编辑值
```

### 2.2 地区条件处理

- 候选必须同时满足姓名+证件号匹配和当前地区条件。
- 地区信息缺失的候选：**无法核验地区条件，不得视为匹配成功**。该候选应被排除或标记为不可确认，具体处理方式为未决前提（见第六节）。
- 查询接口的返回顺序不是授权证据，不能以"排在前面"推断地区匹配。
- 需区分三种状态：
  - **确无候选**：姓名+证件号无匹配记录。
  - **明确地区不符**：有匹配记录但地区条件不满足。
  - **无法核验**：候选地区信息缺失，无法判断是否符合当前地区条件。

### 2.3 时序保护

- 用户修改姓名或证件号后，之前发起的候选查询结果必须失效。
- 请求未结束时再次修改姓名，迟到的旧请求结果必须丢弃。
- 实现方式：每次发起查询时生成递增的请求标识或身份键快照，接收结果时比对当前身份键是否与发起时一致。不指定具体浏览器库或状态管理实现。

### 2.4 日志与隐私

- 日志只记录稳定错误类型和请求标识。
- 不记录姓名、证件号原文。
- 确认框不展示完整证件号，仅展示姓名掩码。

---

## 三、字段写入与异常分支伪代码

### 3.1 补值服务入口

```
function fillFromBaseline(currentForm, identity, currentRegion):
    // identity = { name, idNumber }
    // currentRegion = 当前用户所属地区

    if identity.name is empty or identity.idNumber is empty:
        return { status: "SKIPPED", reason: "身份键不完整" }

    requestId = generateRequestId()
    snapshot = { name: identity.name, idNumber: identity.idNumber }

    candidates = queryCandidates(identity, currentRegion, requestId)

    if candidates is FAILED:
        return { status: "QUERY_FAILED", message: "本次未执行补值，原数据保留" }

    if isStale(requestId, snapshot):
        return { status: "STALE", reason: "身份键已变更" }

    if candidates is EMPTY:
        return { status: "NO_CANDIDATE" }

    // 区分无法核验的候选
    verifiable = filter(candidates, c => c.region is not empty)
    unverifiable = filter(candidates, c => c.region is empty)

    if verifiable is EMPTY and unverifiable is not EMPTY:
        return { status: "UNVERIFIABLE_REGION", message: "候选地区信息缺失，无法确认" }

    sorted = sort(verifiable, by: updateTime DESC, then: recordId ASC)

    userChoice = showConfirmDialog(sorted)  // 展示摘要，不含完整证件号

    if userChoice is CANCEL:
        return { status: "CANCELLED" }

    detail = fetchDetail(userChoice.recordId)

    if detail is FAILED:
        return { status: "DETAIL_FAILED", message: "本次未执行补值，原数据保留" }

    if detail.recordId != userChoice.recordId:
        return { status: "MISMATCH", message: "详情信息不一致，已停止写入" }

    result = applyFields(currentForm, detail)

    return { status: "APPLIED", fields: result }
```

### 3.2 字段写入

```
function applyFields(currentForm, detail):
    applied = []

    // 联系电话
    if isEmpty(currentForm.phone) and isNotEmpty(detail.phone):
        currentForm.phone = detail.phone
        applied.add("phone")
    // 目标已有值：不覆盖
    // 目标为空且来源为空：保持空值，不制造占位号码

    // 职业类别（数值）
    if isNull(currentForm.occupation) or currentForm.occupation === "":
        if detail.occupation is not null and detail.occupation !== "":
            currentForm.occupation = detail.occupation
            applied.add("occupation")
    // 注意：occupation === 0 是有效原值，不触发替换
    // 必须使用显式空值比较，不能用 || 或 falsy 判断

    // 常住地址
    if isEmpty(currentForm.residentAddress) and isNotEmpty(detail.residentAddress):
        currentForm.residentAddress = detail.residentAddress
        applied.add("residentAddress")

    return applied
```

### 3.3 空值判断辅助

```
function isEmpty(value):
    return value === null or value === undefined or value === ""

function isNotEmpty(value):
    return not isEmpty(value)

// 职业类别专用：0 是有效值
function isOccupationEmpty(value):
    return value === null or value === undefined or value === ""
```

### 3.4 时序保护

```
function isStale(requestId, snapshot):
    currentName = getCurrentFormName()
    currentIdNumber = getCurrentFormIdNumber()
    return currentName != snapshot.name or currentIdNumber != snapshot.idNumber
    // 或使用递增 requestId 比对
```

### 3.5 取消与离开

```
function onCancel():
    // 不调用 applyFields
    // 不清空已保存原值
    // 不清空当前未保存的编辑值
    closeDialog()

function onLeaveEditPage():
    // 不触发补值写入
    // 保留表单当前状态
```

---

## 四、按业务行为组织的测试表

| 编号 | 业务行为 | 前置条件 | 操作 | 预期结果 | 依据 |
|------|----------|----------|------|----------|------|
| T01 | 基础调查编辑状态发起查询 | 表单有姓名+证件号 | 进入编辑状态 | 发起候选查询，携带身份键+当前地区 | 条目1 |
| T02 | 修改姓名后旧候选失效 | 已有候选结果 | 修改姓名 | 旧候选不再可用，不沿用旧身份详情 | 条目1 |
| T03 | 追踪调查路由参数不当作基线编号 | 路由有调查记录编号 | 进入编辑状态 | 复用服务接收明确身份键+地区，不读取路由参数作为基线编号 | 条目2 |
| T04 | 确认框不展示完整证件号 | 有候选 | 弹出确认框 | 仅展示姓名掩码，不含完整证件号 | 条目3 |
| T05 | 日志不记录姓名证件号 | 查询执行 | 查看日志 | 仅记录错误类型和请求标识 | 条目3 |
| T06 | 目标电话为空、来源有值 | 目标 phone=null，来源 phone="138..." | 同意补值 | phone 被补入 | 条目4 |
| T07 | 目标电话已有值 | 目标 phone="139..."，来源 phone="138..." | 同意补值 | phone 保持"139..."不变 | 条目4 |
| T08 | 目标电话为空、来源也为空 | 目标 phone=null，来源 phone=null | 同意补值 | phone 保持 null，不制造占位号码 | 条目4 |
| T09 | 职业类别为 0 | 目标 occupation=0，来源 occupation=5 | 同意补值 | occupation 保持 0，不替换 | 条目5 |
| T10 | 职业类别为空 | 目标 occupation=null，来源 occupation=5 | 同意补值 | occupation 补入 5 | 条目5 |
| T11 | 常住地址补值 | 目标 residentAddress=""，来源有值 | 同意补值 | residentAddress 补入 | 条目6 |
| T12 | 不写入非白名单字段 | 详情含户籍地址等 | 同意补值 | 仅写入联系电话、职业类别、常住地址 | 条目6 |
| T13 | 更新时间相同按编号排序 | 两条候选 updateTime 相同 | 查询候选 | 按 recordId 升序排列，分页/重试顺序稳定 | 条目7 |
| T14 | 详情编号不一致 | 同意候选 A，详情返回编号 B | 获取详情 | 停止写入，提示信息不一致 | 条目8 |
| T15 | 迟到旧请求失效 | 请求未结束，用户修改姓名 | 旧请求返回 | 旧结果被丢弃，不写入 | 条目9 |
| T16 | 地区缺失候选 | 候选 region 为空 | 查询候选 | 不视为匹配成功，标记为无法核验 | 条目10 |
| T17 | 确无候选 | 姓名+证件号无匹配 | 查询候选 | 不弹确认框，流程结束 | 条目10 |
| T18 | 明确地区不符 | 有匹配但地区不满足 | 查询候选 | 不视为可补值候选 | 条目10 |
| T19 | 用户取消 | 确认框弹出 | 点击取消 | 不写入，保留全部原值和未保存编辑值 | 条目11 |
| T20 | 关闭弹窗 | 确认框弹出 | 关闭弹窗 | 不写入，保留原值 | 条目11 |
| T21 | 离开编辑页面 | 确认框弹出 | 离开页面 | 不写入，保留原值 | 条目11 |
| T22 | 查询失败 | 网络异常 | 发起查询 | 提示"本次未执行补值，原数据保留"，不伪装为无匹配 | 条目12 |
| T23 | 详情失败 | 网络异常 | 获取详情 | 提示失败，保留原值 | 条目12 |
| T24 | 错误提示不含内部信息 | 查询/详情失败 | 查看提示 | 不含堆栈、数据库错误文本、上游地址 | 条目12 |
| T25 | 无候选不弹确认框 | 无匹配记录 | 查询候选 | 不弹确认框 | 约束 |
| T26 | 有候选必须询问 | 有匹配候选 | 查询候选 | 弹出确认框，不自动写入 | 约束 |

---

## 五、材料核对与内容取舍

### 5.1 材料对应表

| 材料 | 覆盖范围 | 本方案采用情况 |
|------|----------|----------------|
| `materials/software/current-brief.md` | 匹配条件、字段白名单、接口职责、排序规则、空值规则、分支边界 | 直接采用，作为主依据 |
| `materials/software/tests/layout-example.txt` | 排版和来源识别测试例子 | 排除，不作为业务事实 |

### 5.2 确定可采用的信息

- 匹配使用姓名+证件号+当前地区，两个条件都满足才属于可补值候选。
- 字段白名单：联系电话、职业类别、常住地址。
- 候选摘要含编号、地区、更新时间、姓名掩码。
- 排序：更新时间降序，同时间按编号升序。
- 空值规则：仅 null 或空字符串可补入；0、false、非空字符串为有效原值。
- 取消/关闭/离开不写入，保留原值。
- 详情编号不一致停止写入。
- 迟到旧请求失效。
- 地区缺失不视为匹配成功。
- 日志不记录姓名证件号。

### 5.3 无法核验的部分

- 候选地区信息缺失时的具体处理方式（排除、标记、还是其他）——未决前提。
- 当前用户所属地区的具体来源和传递方式——未在资料中说明，实施时需确认。
- 详情接口返回的完整字段列表——资料仅说明返回补值字段，未列出全部字段名。

### 5.4 冲突与排除

- `layout-example.txt` 中的 CSV、Python、收入、准确率等均为排版测试例子，与本题无关，已排除。
- 附件中展示的其他地址种类（户籍地址、单位地址、紧急联系人地址）仅作背景，不纳入字段白名单。

---

## 六、未决前提

| 编号 | 未决事项 | 影响范围 | 当前处理 |
|------|----------|----------|----------|
| U01 | 候选记录地区信息缺失、无法核验是否符合当前用户所属地区条件时，该候选应如何处理 | 候选筛选、确认框展示、补值执行 | 暂不确定，保留为未决前提，实施前先确认；不得绕过地区匹配条件 |

**说明**：在 U01 确认前，方案中将地区缺失的候选标记为"无法核验"，不视为匹配成功，不进入可补值候选列表。具体是排除、提示用户、还是其他处理方式，需在实施前由业务方确认。

---

## 七、交接摘要

### 采用的资料

- `materials/software/current-brief.md`：直接说明地区匹配表单安全补值的业务条件、字段白名单、接口职责、排序规则、空值规则和分支边界。

### 被排除的无关示例

- `materials/software/tests/layout-example.txt`：排版和来源识别测试例子，含 CSV、Python、收入、准确率等，与本题无关。

### 尚缺的关键证据

- 候选地区信息缺失时的具体处理方式（U01）。
- 当前用户所属地区的具体来源和传递方式。
- 详情接口返回的完整字段列表。

### 明确冲突

- 无。资料内部一致，未发现与当前口径不同的旧写法。

### 三种状态区分

| 状态 | 内容 |
|------|------|
| 已有充分依据可直接使用 | 匹配条件、字段白名单、排序规则、空值规则、取消行为、详情编号校验、时序保护、日志隐私 |
| 仅作为候选建议需选择 | 地区缺失候选的处理方式（U01） |
| 因缺少关键资料暂不能执行 | 地区缺失候选的最终处理逻辑；当前用户所属地区的具体传递方式 |