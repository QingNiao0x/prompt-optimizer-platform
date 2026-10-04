# 地区基线自动匹配与表单补值实现方案

## 1. 背景与现有调用关系

### 1.1 模块与复用关系

系统包含两个调查模块：**基础调查**与**追踪调查**。二者复用同一份补值服务（以下称 `SupplementService`），该服务不感知页面状态，只接收调用方显式传入的身份键与地区参数。

```
基础调查页面 ──┐
              ├──> SupplementService（同一实例/同一接口契约）
追踪调查页面 ──┘
```

- **基础调查**：进入编辑状态时，从当前表单中读取已有的姓名与证件号，结合当前用户所属地区，发起候选查询。
- **追踪调查**：路由参数仅用于定位当前调查记录；页面从该记录中解析出姓名、证件号后，连同当前用户所属地区一并显式传给 `SupplementService`。路由参数本身**不得**被当作基线记录编号传入补值服务。

### 1.2 现有接口与调用时序

| 接口 | 返回内容 | 调用时机 |
|---|---|---|
| 候选查询接口 | 候选摘要列表（编号、地区、更新时间、姓名掩码） | 进入编辑状态且身份键齐全时 |
| 详情接口 | 指定编号候选的补值字段（联系电话、职业类别、常住地址） | 用户确认某条候选后、写入表单前 |

**时序**：

```
进入编辑状态 → 身份键齐全？ → 发起候选查询 → 展示候选摘要列表
→ 用户确认某条候选 → 调用详情接口（携带该候选编号）
→ 校验返回编号与用户确认编号一致 → 按白名单与空值规则写入表单
```

### 1.3 路由与鉴权兼容

- 路由结构不变：基础调查与追踪调查的现有路由路径、参数格式保持原样。
- 鉴权行为不变：补值服务不引入新的鉴权逻辑；当前用户所属地区仍由现有鉴权上下文提供，服务只接收该值作为参数。
- 查询接口与详情接口的请求方式、响应结构保持兼容，仅在业务处理层增加地区核验与编号一致性校验。

---

## 2. 两个模块共用的处理步骤

### 步骤 0：进入编辑状态

1. 页面加载当前调查记录。
2. 从记录中提取姓名与证件号。
3. 若两者均非空，生成一个**身份键指纹**（如 `name + "|" + idNo` 的不可逆摘要，仅用于前端时序比对，不用于日志或传输）。
4. 将身份键指纹与当前地区存入本次编辑会话的上下文。

### 步骤 1：发起候选查询

1. 调用 `SupplementService.queryCandidates({ name, idNo, region })`。
2. 请求发出时记录当前身份键指纹，作为该请求的**有效标记**。
3. 若请求未返回前用户修改了姓名或证件号，则旧请求的响应到达时直接丢弃（见步骤 5）。

### 步骤 2：处理候选查询响应

1. 对返回的每条候选摘要，检查其地区字段：
   - 地区为空或缺失 → 标记为"无法核验"，**排除**出候选列表，单独归入 `unverifiable` 集合。
   - 地区与当前用户所属地区不一致 → 排除，归入 `regionMismatch` 集合（不展示）。
   - 地区一致 → 进入可展示候选列表。
2. 对可展示候选列表排序：按更新时间降序，时间相同按记录编号升序。
3. 若可展示候选列表为空：
   - 若存在"无法核验"记录 → 提示"存在地区信息缺失、无法核验的候选记录，本次已排除"。
   - 若仅存在地区不符记录 → 提示"未找到符合当前地区的匹配候选"。
   - 若查询本身返回空 → 提示"未找到匹配候选"。
   - **以上情况均不弹确认框。**
4. 若有可展示候选，弹出确认框，展示候选摘要（编号、地区、更新时间、姓名掩码）。**不得展示完整证件号。**

### 步骤 3：用户确认候选

1. 用户点击某条候选的"确认补值"。
2. 记录用户确认的候选编号 `confirmedId`。
3. 调用详情接口 `SupplementService.getDetail({ candidateId: confirmedId })`。

### 步骤 4：详情响应校验与写入

1. 校验详情响应中的记录编号是否等于 `confirmedId`。
   - 不一致 → **停止写入**，提示"候选信息不一致，本次未执行补值，原数据保留"。
2. 校验通过后，按字段白名单与空值规则逐字段判定写入（见第 3 节伪代码）。

### 步骤 5：时序保护（迟到请求失效）

1. 每次发起候选查询时，将当前身份键指纹存入一个会话级变量 `activeRequestFingerprint`。
2. 响应到达时，比较响应对应的请求指纹与当前 `activeRequestFingerprint`：
   - 不一致 → 丢弃该响应，不更新候选列表，不弹确认框。
   - 一致 → 正常处理。
3. 用户修改姓名或证件号时，立即更新 `activeRequestFingerprint` 并清空当前候选列表与确认框状态。

### 步骤 6：取消 / 关闭 / 离开

- 用户点击取消、关闭弹窗、或离开编辑页面：**不执行任何字段写入**。
- 已保存的原值和当前未保存的编辑值均保持原样，不被清空或重置。

---

## 3. 字段写入与异常分支伪代码

```
// ========== 常量定义 ==========
ALLOWED_FIELDS = ["contactPhone", "occupationCategory", "residentialAddress"]

// ========== 空值判定（关键：不使用逻辑或） ==========
function isEmptyValue(value):
    return value == null || value == ""

// ========== 字段写入判定 ==========
function applySupplement(targetForm, sourceDetail):
    for field in ALLOWED_FIELDS:
        targetValue = targetForm[field]
        sourceValue = sourceDetail[field]

        if isEmptyValue(targetValue) and not isEmptyValue(sourceValue):
            targetForm[field] = sourceValue
        // 其他情况：保持原值不变
        // 注意：targetValue == 0 或 false 时 isEmptyValue 返回 false，不会触发替换

    return targetForm

// ========== 候选查询处理 ==========
function handleCandidateQueryResponse(response, requestFingerprint):
    // 时序保护：迟到请求失效
    if requestFingerprint != session.activeRequestFingerprint:
        return  // 丢弃旧响应

    unverifiable = []
    regionMismatch = []
    displayable = []

    for candidate in response.candidates:
        if isEmptyValue(candidate.region):
            unverifiable.append(candidate)
        else if candidate.region != session.currentUserRegion:
            regionMismatch.append(candidate)
        else:
            displayable.append(candidate)

    // 稳定排序：更新时间降序，编号升序
    sort(displayable, key=(updateTime desc, recordId asc))

    if displayable.isEmpty():
        if not unverifiable.isEmpty():
            showMessage("存在地区信息缺失、无法核验的候选记录，本次已排除。未找到可补值候选。")
        else if not regionMismatch.isEmpty():
            showMessage("未找到符合当前地区的匹配候选。")
        else:
            showMessage("未找到匹配候选。")
        return  // 不弹确认框

    showConfirmDialog(displayable)  // 摘要仅含编号、地区、更新时间、姓名掩码

// ========== 详情获取与写入 ==========
function handleUserConfirm(confirmedCandidateId):
    session.confirmedCandidateId = confirmedCandidateId

    try:
        detail = SupplementService.getDetail(candidateId=confirmedCandidateId)
    catch (NetworkError):
        showMessage("网络异常，本次未执行补值，原数据已保留。")
        return
    catch (OtherError):
        showMessage("获取候选详情失败，本次未执行补值，原数据已保留。")
        return

    // 编号一致性校验
    if detail.recordId != confirmedCandidateId:
        showMessage("候选信息不一致，本次未执行补值，原数据已保留。")
        return

    // 执行白名单写入
    applySupplement(currentForm, detail)

// ========== 身份键变更处理 ==========
function onIdentityKeyChanged():
    session.activeRequestFingerprint = generateFingerprint(currentName, currentIdNo)
    clearCandidateList()
    closeConfirmDialog()
    // 不清空表单字段

// ========== 取消 / 关闭 / 离开 ==========
function onCancelOrCloseOrLeave():
    closeConfirmDialog()
    // 不调用 applySupplement，不修改任何表单字段
    // 已保存原值与未保存编辑值均保持不变
```

---

## 4. 按业务行为组织的测试表

| 编号 | 业务行为 | 输入条件 | 预期结果 |
|---|---|---|---|
| T01 | 正常补值-目标为空 | 目标联系电话为 null，来源电话有值 | 补入来源电话 |
| T02 | 正常补值-目标为空字符串 | 目标职业类别为 ""，来源为有效类别 | 补入来源职业类别 |
| T03 | 正常补值-目标已有值 | 目标联系电话已有值，来源电话更新 | 保持目标原值，不覆盖 |
| T04 | 边界-职业类别为零 | 目标职业类别为 0，来源为其他类别 | 保持 0，不触发替换 |
| T05 | 边界-目标为 false | 目标某字段为 false，来源有值 | 保持 false，不触发替换 |
| T06 | 边界-目标与来源均为空 | 目标电话为空，来源电话也为空 | 保持空值，不制造占位号码 |
| T07 | 白名单-非白名单字段 | 来源详情含户籍地址等非白名单字段 | 不写入任何非白名单字段 |
| T08 | 排序-时间不同 | 两条候选更新时间不同 | 按更新时间降序排列 |
| T09 | 排序-时间相同 | 两条候选更新时间相同 | 按记录编号升序排列，多次刷新顺序不变 |
| T10 | 地区核验-地区缺失 | 候选记录地区字段为 null 或空 | 排除该候选，单独提示"无法核验"，不弹确认框 |
| T11 | 地区核验-地区不符 | 候选地区与当前用户地区不一致 | 排除该候选，提示未找到符合地区候选 |
| T12 | 地区核验-无候选 | 查询返回空列表 | 提示未找到匹配候选，不弹确认框 |
| T13 | 确认框隐私 | 有可展示候选 | 确认框展示编号、地区、更新时间、姓名掩码；不展示完整证件号 |
| T14 | 详情编号一致 | 用户确认编号 A，详情返回编号 A | 正常执行白名单写入 |
| T15 | 详情编号不一致 | 用户确认编号 A，详情返回编号 B | 停止写入，提示信息不一致，原数据保留 |
| T16 | 时序保护-迟到请求 | 请求 A 未返回时用户修改姓名，随后 A 返回 | A 的响应被丢弃，不更新候选列表，不弹确认框 |
| T17 | 时序保护-身份键变更 | 用户修改证件号后旧候选已展示 | 旧候选列表清空，确认框关闭，表单字段不变 |
| T18 | 取消操作 | 用户点击取消 | 不写入任何字段，已保存原值与未保存编辑值均不变 |
| T19 | 关闭弹窗 | 用户关闭确认弹窗 | 不写入任何字段，表单字段不变 |
| T20 | 离开编辑页面 | 用户导航离开 | 不写入任何字段，表单字段不变 |
| T21 | 查询网络失败 | 候选查询请求网络异常 | 提示"本次未执行补值，原数据保留"，不弹确认框，不泄露堆栈/内部地址 |
| T22 | 详情网络失败 | 详情请求网络异常 | 提示"本次未执行补值，原数据保留"，不写入字段 |
| T23 | 日志隐私 | 任意查询或详情操作 | 日志仅含稳定错误类型与请求标识，不含姓名或证件号原文 |
| T24 | 追踪调查-路由参数 | 追踪调查页面路由参数为记录 ID | 路由参数仅用于定位记录；补值服务接收的是从记录解析出的姓名、证件号与当前地区 |
| T25 | 基础调查-进入编辑 | 基础调查进入编辑状态，姓名与证件号已有值 | 自动发起候选查询 |
| T26 | 基础调查-身份键不全 | 姓名或证件号为空 | 不发起候选查询，不弹确认框 |

---

## 5. 资料引用

| 来源 | 类型 | 引用内容 |
|---|---|---|
| `materials/software/current-brief.md` | **正式现行说明** | 匹配使用姓名与证件号两个键，且记录必须符合当前用户所属地区条件；字段白名单仅含联系电话、职业类别、常住地址；候选按更新时间降序、编号升序；目标值为 null 或空字符串时允许补入；0、false 和非空字符串为有效原值；用户取消保持原值；追踪调查路由参数不得当作基线记录编号；候选摘要含编号、地区、更新时间、姓名掩码；确认框不展示完整证件号；日志不记录姓名或证件号原文；职业类别为零不触发替换；详情编号不一致停止写入；迟到旧请求失效；地区缺失不是匹配成功；网络失败提示面向录入人员。 |
| `materials/software/tests/layout-example.txt` | **测试示例（非生产事实）** | 仅用于排版与来源识别验证；其中 CSV、Python、100 万元、准确率 99% 等内容均非本题业务事实，不得作为生产接口或业务规则依据。 |