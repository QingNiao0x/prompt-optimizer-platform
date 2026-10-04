# 地区基线自动匹配与表单补值实现方案

## 一、背景与现有调用关系

### 1.1 业务背景

基础调查与追踪调查两个模块在编辑状态下，允许用户基于当前表单中的**姓名**与**证件号**发起基线候选查询，将基线记录中的部分字段补入当前表单。补值必须满足：

- 身份键匹配：姓名 + 证件号；
- 地区条件匹配：记录必须符合当前用户所属地区；
- 字段白名单：仅联系电话、职业类别、常住地址；
- 用户显式同意后才写入，写入前重新拉取最新详情。

### 1.2 现有接口与调用关系（保持兼容）

| 接口 | 作用 | 兼容要求 |
| --- | --- | --- |
| 候选查询接口 | 返回候选摘要（编号、地区、更新时间、姓名掩码） | 路径、鉴权、返回结构不变 |
| 单条详情接口 | 返回补值字段（联系电话、职业类别、常住地址） | 路径、鉴权、返回结构不变 |
| 表单保存接口 | 保存当前调查记录 | 不因补值流程改变行为 |

调用关系：

```
基础调查编辑页 ─┐
                ├─→ 共用补值服务 ─→ 候选查询接口
追踪调查编辑页 ─┘                 └→ 单条详情接口
```

两个模块**共用同一补值服务**，服务入参必须显式携带身份键与当前地区，不得隐式读取页面状态。

### 1.3 关键前提（来自现行资料）

- 候选排序：更新时间降序，时间相同按记录编号升序，分页/重试后顺序稳定。
- 目标值判定：仅 `null` 或空字符串允许补入；`0`、`false`、非空字符串均为有效原值。
- 取消/关闭/离开编辑页：不得写入，不得清空已保存原值与未保存编辑值。
- 详情返回编号与已同意候选编号不一致：停止写入并提示。
- 迟到响应：用户修改姓名后，旧请求必须失效。
- 日志：只记录稳定错误类型与请求标识，不记录姓名、证件号原文。
- 确认框：只展示姓名掩码，不展示完整证件号。

### 1.4 待确认项（唯一保留）

**候选地区信息缺失或无法核验当前地区条件时的处理**：按用户明确决定，**排除出可补值候选**，并单独说明"无法核验"，不得伪装为无匹配或查询失败。此决定已明确，不再作为待确认项。

---

## 二、两个模块共用的处理步骤

### 2.1 触发条件

- 基础调查：进入编辑状态，且姓名与证件号均非空。
- 追踪调查：进入编辑状态，且姓名与证件号均非空；路由参数仅用于定位当前调查记录，**不得**当作基线记录编号。

### 2.2 处理流程

1. **采集身份键与地区**：从当前表单读取姓名、证件号；从当前用户上下文读取所属地区。三者作为服务入参显式传入。
2. **发起候选查询**：调用候选查询接口，携带身份键与地区条件。
3. **结果分类**：
   - 无候选：不弹确认框，静默结束。
   - 有候选：弹出确认框，展示候选摘要（编号、地区、更新时间、姓名掩码）。
   - 地区缺失/无法核验：排除该候选，单独提示"无法核验地区条件"，与"无匹配""地区不符"区分。
   - 查询失败：保留原值，提示"本次未执行补值，原数据保留"。
4. **用户选择**：
   - 取消/关闭/离开编辑页：不写入，保留全部原值。
   - 同意某候选：记录候选编号，进入详情拉取。
5. **拉取最新详情**：在写入前调用详情接口，携带已同意候选编号。
6. **一致性校验**：详情返回编号必须与已同意候选编号一致；不一致则停止写入并提示。
7. **字段补值**：按白名单逐字段判断，仅当目标值为 `null` 或空字符串时写入来源值。
8. **写入表单**：更新表单字段，不触发保存接口的额外行为。

### 2.3 时序保护

- 用户修改姓名或证件号后，之前发起的候选查询与详情请求全部失效。
- 实现方式：为每次查询生成请求标识（如递增序号或 UUID），响应返回时比对当前标识，不匹配则丢弃。
- 不指定具体浏览器库或状态管理实现。

---

## 三、字段写入与异常分支伪代码

### 3.1 共用补值服务

```java
// 入参显式携带身份键与地区，不读取页面状态
class BaselineFillRequest {
    String name;
    String idNumber;
    String currentRegion;
    String requestId; // 用于时序保护
}

class BaselineFillService {

    // 候选查询
    List<CandidateSummary> queryCandidates(BaselineFillRequest req) {
        try {
            List<CandidateSummary> raw = candidateQueryApi.query(
                req.name, req.idNumber, req.currentRegion);
            List<CandidateSummary> valid = new ArrayList<>();
            List<CandidateSummary> unverifiable = new ArrayList<>();
            for (CandidateSummary c : raw) {
                if (c.region == null || !regionVerifiable(c, req.currentRegion)) {
                    unverifiable.add(c); // 地区缺失或无法核验，排除
                } else if (regionMatches(c, req.currentRegion)) {
                    valid.add(c);
                }
                // 明确地区不符：不加入任何列表，按无匹配处理
            }
            // 排序：更新时间降序，时间相同按编号升序
            valid.sort(Comparator
                .comparing(CandidateSummary::getUpdateTime).reversed()
                .thenComparing(CandidateSummary::getRecordId));
            return valid;
        } catch (Exception e) {
            log.warn("candidate_query_failed requestId={}", req.requestId);
            throw new FillException("本次未执行补值，原数据保留");
        }
    }

    // 详情拉取与写入
    FillResult applyFill(BaselineFillRequest req, String agreedCandidateId, FormTarget target) {
        try {
            CandidateDetail detail = candidateDetailApi.get(agreedCandidateId);
            if (!agreedCandidateId.equals(detail.recordId)) {
                return FillResult.fail("候选信息不一致，已停止写入");
            }
            // 白名单字段补值
            if (isBlank(target.phone)) {
                target.phone = detail.phone; // 来源为空则保持空
            }
            if (isBlank(target.occupation)) {
                target.occupation = detail.occupation; // 0 为有效值，不替换
            }
            if (isBlank(target.address)) {
                target.address = detail.address;
            }
            return FillResult.ok();
        } catch (Exception e) {
            log.warn("detail_fetch_failed requestId={} candidateId={}", req.requestId, agreedCandidateId);
            return FillResult.fail("本次未执行补值，原数据保留");
        }
    }

    // 空值判定：仅 null 或空字符串
    boolean isBlank(String v) {
        return v == null || v.isEmpty();
    }
}
```

### 3.2 前端处理（Vue 3 + TypeScript 伪代码）

```typescript
// 时序保护：每次查询递增 requestSeq
let requestSeq = 0;

async function onIdentityChanged() {
    requestSeq++;
    const seq = requestSeq;
    const req = { name: form.name, idNumber: form.idNumber, currentRegion: user.region, requestId: String(seq) };
    try {
        const candidates = await queryCandidates(req);
        if (seq !== requestSeq) return; // 迟到响应丢弃
        if (candidates.length === 0) return; // 无候选不弹框
        showConfirmDialog(candidates); // 展示掩码，不展示完整证件号
    } catch (e) {
        if (seq !== requestSeq) return;
        showMessage("本次未执行补值，原数据保留");
    }
}

async function onUserAgree(candidateId: string) {
    const seq = requestSeq;
    try {
        const result = await applyFill(req, candidateId, form);
        if (seq !== requestSeq) return;
        if (!result.ok) showMessage(result.message);
    } catch (e) {
        showMessage("本次未执行补值，原数据保留");
    }
}

function onCancelOrClose() {
    // 不写入，不清空已保存原值与未保存编辑值
}
```

### 3.3 异常分支汇总

| 分支 | 处理 |
| --- | --- |
| 无候选 | 不弹确认框，静默结束 |
| 地区缺失/无法核验 | 排除候选，单独提示"无法核验地区条件" |
| 明确地区不符 | 按无匹配处理 |
| 查询失败 | 保留原值，提示"本次未执行补值，原数据保留" |
| 详情失败 | 保留原值，提示同上 |
| 详情编号不一致 | 停止写入，提示"候选信息不一致" |
| 用户取消/关闭/离开 | 不写入，保留全部原值 |
| 迟到响应 | 丢弃，不写入 |
| 目标值非空（含 0、false） | 不替换 |
| 目标值为空、来源为空 | 保持空值，不制造占位 |

---

## 四、测试清单（按业务行为组织）

| 编号 | 业务行为 | 前置条件 | 操作 | 预期结果 | 依据 |
| --- | --- | --- | --- | --- | --- |
| T01 | 身份键匹配 + 地区匹配 | 姓名、证件号、地区均匹配 | 发起查询 | 返回候选，弹确认框 | 现行资料 |
| T02 | 身份键匹配 + 地区不符 | 地区不匹配 | 发起查询 | 按无匹配处理，不弹框 | 现行资料 |
| T03 | 地区缺失/无法核验 | 候选地区为 null | 发起查询 | 排除候选，提示"无法核验地区条件" | 用户决定 |
| T04 | 无候选 | 无匹配记录 | 发起查询 | 不弹确认框，静默结束 | 现行资料 |
| T05 | 查询失败 | 接口异常 | 发起查询 | 保留原值，提示"本次未执行补值，原数据保留" | 现行资料 |
| T06 | 详情失败 | 详情接口异常 | 同意候选后 | 保留原值，提示同上 | 现行资料 |
| T07 | 详情编号不一致 | 详情返回另一编号 | 同意候选后 | 停止写入，提示"候选信息不一致" | 现行资料 |
| T08 | 用户取消 | 弹确认框 | 点击取消 | 全部字段原值不变 | 现行资料 |
| T09 | 关闭弹窗 | 弹确认框 | 关闭 | 全部字段原值不变 | 现行资料 |
| T10 | 离开编辑页 | 弹确认框 | 离开 | 不写入，原值与编辑值均保留 | 现行资料 |
| T11 | 目标电话为空、来源有值 | 目标 phone=null | 同意候选 | 补入来源电话 | 现行资料 |
| T12 | 目标电话已有值 | 目标 phone="123" | 同意候选 | 不覆盖 | 现行资料 |
| T13 | 目标电话为空、来源为空 | 目标 phone=null，来源 null | 同意候选 | 保持空值，不制造占位 | 现行资料 |
| T14 | 职业类别为 0 | 目标 occupation=0 | 同意候选 | 不替换（0 为有效值） | 现行资料 |
| T15 | 职业类别为 false | 目标 occupation=false | 同意候选 | 不替换 | 现行资料 |
| T16 | 常住地址补值 | 目标 address=null | 同意候选 | 补入来源地址 | 现行资料 |
| T17 | 白名单外字段 | 来源含其他字段 | 同意候选 | 不写入其他字段 | 现行资料 |
| T18 | 排序稳定性 | 两条候选更新时间相同 | 分页/重试 | 按编号升序，顺序不变 | 现行资料 |
| T19 | 迟到响应 | 查询未返回时修改姓名 | 修改姓名 | 旧请求失效，不写入 | 现行资料 |
| T20 | 确认框展示 | 有候选 | 弹框 | 只展示姓名掩码，不展示完整证件号 | 现行资料 |
| T21 | 日志脱敏 | 任意流程 | 查看日志 | 只记录错误类型与请求标识，不记录姓名/证件号 | 现行资料 |
| T22 | 追踪调查路由参数 | 路由含调查记录编号 | 发起查询 | 不当作基线记录编号，显式传身份键与地区 | 现行资料 |
| T23 | 基础调查修改身份键 | 已有候选 | 修改姓名或证件号 | 旧候选失效，不沿用旧详情 | 现行资料 |

---

## 五、资料引用说明

- **正式说明**：`materials/software/current-brief.md`，用于核对匹配条件、字段白名单、接口行为、排序规则、空值判定、时序保护、日志脱敏等。
- **测试示例**：`materials/software/tests/layout-example.txt`，仅用于排版与来源识别，**不是本题事实**，不得作为生产接口或业务依据。
- **旧版草稿**：本方案未引用旧版草稿；如后续出现，须与正式说明分开标注。
- **待确认项**：候选地区缺失/无法核验的处理已由用户明确决定（排除并单独说明），不再保留为待确认项。

---

## 六、交付边界

- 本方案为只读分析与方案交付，不产生代码修改、数据库迁移、命令执行或部署。
- 现有查询接口、路由、鉴权行为保持兼容。
- 两个模块（基础调查、追踪调查）共用同一补值服务，入参显式携带身份键与地区。
- 示例与测试资料仅用于验证分支，不作为生产接口与业务事实。