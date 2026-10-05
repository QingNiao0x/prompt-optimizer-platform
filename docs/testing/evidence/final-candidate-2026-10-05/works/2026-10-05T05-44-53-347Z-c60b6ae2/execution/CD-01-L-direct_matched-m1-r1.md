# 地区基线自动匹配与表单补值实现方案

> 说明：本方案为只读分析与方法交付，不包含代码修改、数据库迁移或部署动作。文中“现行资料”指 `materials/software/current-brief.md`；“排版示例”指 `materials/software/tests/layout-example.txt`，仅用于验证分支与来源识别，不作为生产接口或业务事实。

## 1. 背景与现有调用关系

### 1.1 目标与读者

- 目标：在基础调查与追踪调查两个模块中，复用同一份补值服务，实现“地区基线自动匹配 + 用户确认 + 安全补值”。
- 读者：后端与前端实现人员、测试人员、业务复核人员。
- 术语：
  - **补值服务**：接收身份键与当前地区，返回候选摘要、读取候选详情、按白名单与空值规则写入表单的共用服务。
  - **身份键**：姓名与证件号两个键，二者同时参与匹配。
  - **地区条件**：记录必须符合当前用户所属地区，才属于可补值候选。
  - **候选摘要**：编号、地区、更新时间、姓名掩码。
  - **详情编号一致性**：用户已同意的候选编号与详情接口返回的编号必须一致。

### 1.2 现有调用关系（保持兼容）

| 环节 | 现有行为 | 本次要求 |
| --- | --- | --- |
| 查询接口 | 返回候选摘要 | 保持接口、路由、鉴权行为兼容 |
| 详情接口 | 读取单条详情，返回补值字段 | 在用户同意后、写入前获取最新详情 |
| 路由 | 追踪调查路由参数定位当前调查记录 | 路由参数不得当作基线记录编号 |
| 鉴权 | 现有鉴权行为 | 不变 |

### 1.3 两个模块如何复用同一补值服务

- 基础调查：进入编辑状态时，根据当前已有的姓名与证件号发起候选查询；用户修改这两个键后，旧候选失效。
- 追踪调查：路由参数只负责定位当前调查记录；复用服务必须接收明确的身份键与当前地区，不得暗中读取另一页面状态。
- 共用点：候选查询、候选摘要展示、用户确认、写入前获取最新详情、字段补值、取消与异常分支，均由同一补值服务承载；两个模块只负责提供身份键、当前地区与目标表单引用。

### 1.4 材料对应表

| 材料 | 覆盖范围 | 结论用途 |
| --- | --- | --- |
| `materials/software/current-brief.md` | 匹配键、地区条件、字段白名单、排序、空值规则、取消、时序保护、编号一致性、失败提示 | 直接支撑本方案业务条件 |
| `materials/software/tests/layout-example.txt` | 排版与来源识别测试例子（CSV、Python、收入、准确率） | 仅用于验证分支，不作为生产接口与业务事实 |

## 2. 两个模块共用的处理步骤

1. **候选查询**
   - 输入：姓名、证件号、当前地区、请求标识。
   - 条件：姓名与证件号两个键同时匹配，且记录符合当前用户所属地区条件。
   - 排序：更新时间降序；时间相同按记录编号升序。
   - 结果分类：确无候选、明确地区不符、无法核验（地区缺失）。
2. **候选摘要展示**
   - 展示：编号、地区、更新时间、姓名掩码。
   - 禁止：确认框不得展示完整证件号。
   - 无候选时不弹确认框；有匹配候选时必须询问用户。
3. **用户确认**
   - 用户选择一条候选并确认后，记录已同意候选编号。
   - 用户取消、关闭弹窗或离开编辑页面：不得补写字段，原值与未保存编辑值均不清空。
4. **写入前获取最新详情**
   - 在用户同意后、写入表单前，调用详情接口获取该候选最新详情。
   - 校验详情编号与已同意候选编号一致；不一致则停止写入并提示信息不一致。
5. **字段补值**
   - 白名单：联系电话、职业类别、常住地址。
   - 空值判断：仅当目标值为 `null` 或空字符串时允许补入来源值。
   - 保留：`0`、`false` 和非空字符串均为有效原值，必须保留。
6. **取消与异常分支**
   - 取消/关闭/离开：不写入，不清空。
   - 查询或详情失败：保留原值，显示面向录入人员的失败提示，不得伪装为没有匹配记录。
   - 网络失败提示：说明本次未执行补值且原数据保留；不附带内部堆栈、数据库错误文本或上游服务地址。
   - 日志：只记录稳定错误类型和请求标识，不记录姓名或证件号原文。

## 3. 字段写入与异常分支伪代码

```text
// 输入：identityKeys = {name, idNumber}, currentRegion, targetForm, requestId
// 输出：写入结果或失败提示

function queryCandidates(identityKeys, currentRegion, requestId):
    if identityKeys.name is null/empty or identityKeys.idNumber is null/empty:
        return Failure("身份键不完整，未执行补值")
    candidates = queryApi(identityKeys, currentRegion, requestId)
    if candidates is error:
        return Failure("查询失败，原数据保留")
    if candidates is empty:
        return NoCandidate("确无候选")
    if candidates has regionMismatch:
        return RegionMismatch("明确地区不符")
    if candidates has regionMissing:
        return Unverifiable("地区缺失，无法核验，保留为待确认项")
    sort candidates by updatedAt desc, recordId asc
    return candidates

function onUserConfirm(candidate):
    agreedCandidateId = candidate.recordId
    detail = getDetailApi(agreedCandidateId, requestId)
    if detail is error:
        return Failure("详情获取失败，原数据保留")
    if detail.recordId != agreedCandidateId:
        return Failure("信息不一致，停止写入")
    return applyPatch(targetForm, detail)

function applyPatch(targetForm, detail):
    whitelist = ["contactPhone", "occupationCategory", "residenceAddress"]
    for field in whitelist:
        targetValue = targetForm[field]
        sourceValue = detail[field]
        if targetValue is null or targetValue == "":
            if sourceValue is not null and sourceValue != "":
                targetForm[field] = sourceValue
            // 来源也为空：保持空值，不制造占位号码
        // 目标已有值：即使来源更新也不得覆盖
    return Success("补值完成")

function onCancelOrLeave():
    // 不写入任何字段
    // 页面已保存原值与未保存编辑值均不清空
    return Cancelled

function onIdentityKeyChanged():
    // 旧候选失效，不能沿用旧身份对应的详情
    invalidatePreviousCandidates()
    // 迟到的旧请求必须失效
    invalidateInFlightRequestsByRequestId()

function onLateResponse(response, currentRequestId):
    if response.requestId != currentRequestId:
        return Ignored("迟到旧请求已失效")
    return response
```

**关键约束说明**

- 空值判断使用 `null` 或空字符串比较，不使用逻辑或表达式，避免 `0` 被误判。
- 职业类别为数值 `0` 时视为有效类别，必须保留。
- 常住地址为单个字符串字段，不包含户籍地址、单位地址或紧急联系人地址。
- 地区缺失不是地区匹配成功；查询接口返回顺序不是授权证据。
- 详情编号一致性校验失败时停止写入，不因部分字段相似而接受响应。

## 4. 按业务行为组织的测试表

| 编号 | 业务行为 | 前置条件 | 操作 | 预期结果 | 状态 | 值域/单位 |
| --- | --- | --- | --- | --- | --- | --- |
| T01 | 正常补值 | 目标电话为空，来源电话有值 | 确认候选 | 电话补入 | 通过 | 字符串 |
| T02 | 已有值不覆盖 | 目标电话已有值，来源更新 | 确认候选 | 电话保持原值 | 通过 | 字符串 |
| T03 | 双方为空 | 目标电话为空，来源电话为空 | 确认候选 | 保持空值，不制造占位号码 | 通过 | 空值 |
| T04 | 职业类别为零 | 目标职业类别为 `0` | 确认候选 | 保留 `0`，不替换 | 通过 | 数值 |
| T05 | 职业类别为空 | 目标职业类别为 `null`，来源有值 | 确认候选 | 补入来源值 | 通过 | 数值 |
| T06 | 常住地址补值 | 目标地址为空，来源有值 | 确认候选 | 补入地址 | 通过 | 字符串 |
| T07 | 白名单外字段 | 详情含其他字段 | 确认候选 | 不写入其他字段 | 通过 | — |
| T08 | 排序稳定 | 两条候选更新时间相同 | 分页/重试 | 按编号升序，顺序不变 | 通过 | 编号 |
| T09 | 取消不写入 | 用户点击取消 | 取消 | 原值与未保存编辑值均不清空 | 通过 | — |
| T10 | 关闭弹窗 | 用户关闭弹窗 | 关闭 | 不写入，不清空 | 通过 | — |
| T11 | 离开编辑页 | 用户离开页面 | 离开 | 不写入，不清空 | 通过 | — |
| T12 | 详情编号不一致 | 已同意候选 A，详情返回 B | 确认后写入前 | 停止写入，提示信息不一致 | 通过 | 编号 |
| T13 | 迟到旧请求 | 请求未结束，用户修改姓名 | 修改姓名 | 旧请求失效 | 通过 | — |
| T14 | 查询失败 | 查询接口报错 | 发起查询 | 保留原值，提示失败 | 通过 | — |
| T15 | 详情失败 | 详情接口报错 | 确认候选 | 保留原值，提示失败 | 通过 | — |
| T16 | 无候选 | 无匹配记录 | 发起查询 | 不弹确认框 | 通过 | — |
| T17 | 明确地区不符 | 记录地区与当前地区不符 | 发起查询 | 提示地区不符 | 通过 | — |
| T18 | 地区缺失 | 候选地区信息缺失 | 发起查询 | 标记无法核验，保留待确认 | 待确认 | — |
| T19 | 确认框隐私 | 候选摘要展示 | 查看确认框 | 不展示完整证件号 | 通过 | — |
| T20 | 日志隐私 | 发生错误 | 查看日志 | 只记录错误类型与请求标识 | 通过 | — |

## 5. 交接摘要

### 5.1 采用的资料

- `materials/software/current-brief.md`：匹配键、地区条件、字段白名单、排序、空值规则、取消、时序保护、编号一致性、失败提示。
- `materials/software/tests/layout-example.txt`：仅用于排版与来源识别测试，不作为生产接口与业务事实。

### 5.2 被排除的无关示例

- CSV、Python、收入 100 万元、准确率 99% 等内容，与本题业务无关，已排除。

### 5.3 尚缺的关键证据

- 候选地区信息缺失、无法核验当前地区条件时的处理方式：当前尚未决定，不得默认补全。该决定影响地区匹配分支、候选展示与写入逻辑，需在实施前确认。
- 地区字段的具体来源与校验方式：现有资料未提供，需补充。

### 5.4 明确冲突

- 暂无发现材料间直接冲突；排版示例中的内容与本题口径不同，已按“仅测试例子”处理。

### 5.5 三种状态区分

- **已有充分依据可直接使用**：匹配键、地区条件、字段白名单、排序、空值规则、取消、时序保护、编号一致性、失败提示。
- **仅作为候选建议需要选择**：无。
- **因缺少关键资料暂不能执行**：地区缺失候选的处理方式，需等待确认。