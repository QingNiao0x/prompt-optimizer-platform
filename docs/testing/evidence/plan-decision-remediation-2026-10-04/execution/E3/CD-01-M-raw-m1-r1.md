# 地区基线自动匹配与表单补值实现方案

## 1. 背景与现有调用关系

系统包含基础调查、追踪调查两个模块，二者复用同一份补值服务。补值服务负责：按“姓名 + 证件号”两个键匹配候选，并要求候选记录符合当前用户所属地区条件；两个条件同时满足才视为可补值候选。

现有接口分工保持不变：

- 查询接口：返回候选摘要（编号、地区、更新时间、姓名掩码），按更新时间降序、时间相同按记录编号升序排列。
- 详情接口：返回单条候选的补值字段。
- 写入时机：用户同意某条候选后、写入表单前，必须重新获取该候选的最新详情。
- 写入范围：仅联系电话、职业类别、常住地址三个字段；其他字段（含目录中的演示字段）不在本次范围。
- 鉴权、路由、查询接口行为保持兼容，不新增或改变现有路由语义。

追踪调查页面的路由参数只用于定位当前调查记录，不能当作基线记录编号；补值服务必须显式接收身份键与当前地区，不得隐式读取另一页面状态。

## 2. 两个模块共用的处理步骤

1. 进入编辑状态后，由页面提供当前姓名、证件号、当前地区，调用查询接口获取候选摘要。
2. 若用户修改姓名或证件号，使已有候选失效，并作废尚未返回的旧请求（迟到响应不得生效）。
3. 无候选时不弹确认框；有候选时必须弹确认框，展示编号、地区、更新时间、姓名掩码，不展示完整证件号。
4. 用户同意某条候选后，调用详情接口获取该候选最新详情；若返回编号与已同意编号不一致，停止写入并提示信息不一致。
5. 写入前逐字段判断：仅当目标值为 null 或空字符串时，才允许补入来源值；0、false、非空字符串均视为有效原值，必须保留。
6. 用户取消、关闭弹窗或离开编辑页面时，不写入任何字段，已保存原值与未保存编辑值均保持不变。
7. 查询或详情失败时，保留原值并给出面向录入人员的提示，说明本次未执行补值且原数据保留；不得伪装为“没有匹配记录”，不暴露堆栈、数据库错误文本或上游地址。
8. 日志只记录稳定错误类型和请求标识，不记录姓名或证件号原文。

## 3. 字段写入与异常分支伪代码

```text
// 伪代码：共用补值流程
function runBaselineFill(module, currentForm, identity, currentRegion, requestId):
    // module 为 BASIC 或 TRACKING，仅用于日志与提示，不改变流程
    if identity.name is blank or identity.idNo is blank:
        return NO_QUERY   // 不发起查询，不提示“无候选”

    candidates = queryCandidates(identity, currentRegion, requestId)
    if candidates failed:
        showUserMessage("本次未执行补值，原数据已保留")
        log(stableErrorType, requestId)
        return FAILED_KEEP_ORIGINAL

    if candidates is empty:
        return NO_CANDIDATE   // 不弹确认框

    // 候选已按更新时间降序、编号升序返回
    selected = showConfirmDialog(candidates)   // 展示编号、地区、更新时间、姓名掩码
    if selected is null:                       // 取消或关闭
        return CANCELLED_KEEP_ORIGINAL

    detail = getCandidateDetail(selected.id, requestId)
    if detail failed:
        showUserMessage("本次未执行补值，原数据已保留")
        log(stableErrorType, requestId)
        return FAILED_KEEP_ORIGINAL

    if detail.id != selected.id:
        showUserMessage("候选信息不一致，已停止补值")
        log(CANDIDATE_MISMATCH, requestId)
        return MISMATCH_STOP

    // 写入前再次确认身份键未变化；若已变化则作废
    if identityChangedSince(selected):
        return STALE_IDENTITY_DISCARD

    applyWhitelistFields(currentForm, detail)
    return SUCCESS


function applyWhitelistFields(form, source):
    // 仅三个白名单字段
    form.phone       = fillIfEmpty(form.phone, source.phone)
    form.occupation  = fillIfEmpty(form.occupation, source.occupation)
    form.address     = fillIfEmpty(form.address, source.address)
    // 其他字段一律不写

function fillIfEmpty(target, source):
    // 明确空值比较，不用逻辑或
    if target is null or target == "":
        if source is null or source == "":
            return target          // 保持空值，不造占位
        return source
    return target                  // 0、false、非空字符串均保留


// 时序保护：用户修改姓名/证件号后
function onIdentityChanged(newIdentity):
    invalidatePendingRequests()    // 迟到旧响应不得生效
    clearCurrentCandidates()
    identity = newIdentity
```

地区缺失处理：候选地区信息缺失、无法核验当前地区条件时，该候选不得视为地区匹配成功，也不得默认补全。方案将其与“确无候选”“明确地区不符”分别处理：无法核验的记录不进入可确认候选集，并记录稳定错误类型；是否允许用户看到“地区待核验”提示，属于尚待明确的业务决定，见第 5 节。

## 4. 测试清单（按业务行为组织）

| 编号 | 业务行为 | 前置条件 | 操作 | 预期结果 |
|---|---|---|---|---|
| T01 | 双键匹配 | 姓名、证件号均匹配且地区符合 | 发起查询 | 返回该候选 |
| T02 | 地区不符 | 姓名、证件号匹配但地区不符 | 发起查询 | 不进入可补值候选 |
| T03 | 地区缺失 | 候选地区信息缺失 | 发起查询 | 不视为匹配成功，不默认补全，记录稳定错误类型 |
| T04 | 无候选 | 无任何匹配 | 发起查询 | 不弹确认框 |
| T05 | 有候选必须确认 | 存在匹配候选 | 发起查询 | 必须弹确认框，展示编号、地区、更新时间、姓名掩码 |
| T06 | 确认框不泄露证件号 | 存在匹配候选 | 查看确认框 | 不展示完整证件号 |
| T07 | 排序稳定 | 两条候选更新时间相同 | 查询并分页/重试 | 按编号升序，顺序不随机变化 |
| T08 | 同意后取最新详情 | 用户同意某候选 | 写入前 | 调用详情接口获取最新详情 |
| T09 | 详情编号不一致 | 详情返回另一编号 | 写入前 | 停止写入并提示信息不一致 |
| T10 | 目标为空、来源有值 | 目标电话为空，来源有值 | 补值 | 补入来源值 |
| T11 | 目标已有值 | 目标电话已有值，来源更新 | 补值 | 不覆盖 |
| T12 | 双方为空 | 目标电话为空，来源也为空 | 补值 | 保持空值，不造占位号码 |
| T13 | 职业类别为 0 | 目标职业类别为 0 | 补值 | 保留 0，不触发替换 |
| T14 | 布尔 false 保留 | 目标字段为 false | 补值 | 保留 false |
| T15 | 非空字符串保留 | 目标字段为非空字符串 | 补值 | 保留原值 |
| T16 | 白名单限制 | 详情含其他字段 | 补值 | 仅写联系电话、职业类别、常住地址 |
| T17 | 用户取消 | 确认框弹出 | 点击取消 | 全部字段原值不变，未保存编辑值不清空 |
| T18 | 关闭弹窗/离开页面 | 确认框弹出 | 关闭或离开 | 不补写任何字段 |
| T19 | 身份键修改使候选失效 | 已有候选 | 修改姓名或证件号 | 旧候选失效，不沿用旧身份详情 |
| T20 | 迟到旧请求失效 | 候选请求未结束 | 再次修改姓名 | 迟到旧响应不得生效 |
| T21 | 查询失败 | 查询接口失败 | 发起查询 | 保留原值，提示本次未执行补值，不伪装无匹配 |
| T22 | 详情失败 | 详情接口失败 | 同意后 | 保留原值，提示本次未执行补值 |
| T23 | 日志脱敏 | 任意失败 | 查看日志 | 只记录稳定错误类型和请求标识，不含姓名、证件号原文 |
| T24 | 追踪调查路由参数 | 追踪页面带路由参数 | 发起补值 | 路由参数不当作基线记录编号，服务显式接收身份键与地区 |
| T25 | 基础调查编辑态 | 基础调查进入编辑 | 发起查询 | 按当前姓名、证件号查询，行为与追踪模块一致 |

## 5. 尚待明确项

- 候选地区信息缺失、无法核验当前地区条件时，除“不视为匹配成功、不默认补全”外，是否允许向用户展示“地区待核验”提示，以及该提示的文案与是否计入候选列表，尚未决定。该决定影响任务目标中的候选呈现与用户确认行为，需业务方确认后再实现。

## 6. 资料引用说明

- 正式说明：`materials/software/current-brief.md`，用于匹配键、地区条件、字段白名单、排序、空值规则、时序保护、日志脱敏等事实。
- 测试示例：`materials/software/tests/layout-example.txt`，仅用于排版与来源识别，其中 CSV、Python、收入、准确率等均非本题事实，不得作为生产接口或业务依据。
- 旧版草稿：未在本次资料中提供，若后续出现，应与正式说明分开引用，不得覆盖 `current-brief.md` 的明确要求。