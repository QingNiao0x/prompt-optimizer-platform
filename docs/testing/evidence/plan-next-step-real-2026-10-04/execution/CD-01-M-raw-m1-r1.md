# 地区基线自动匹配与表单补值实现方案

## 一、背景与现有调用关系

### 1.1 业务背景

基础调查与追踪调查两个模块在编辑状态下，需要根据当前录入的姓名与证件号，从地区基线中匹配候选记录，并在用户确认后，将候选记录中的部分字段补入当前表单。两个模块复用同一份补值服务，但入口、身份键来源和路由语义不同。

### 1.2 现有调用关系

- **查询接口**：返回候选摘要列表，包含编号、地区、更新时间、姓名掩码。不返回完整证件号。
- **详情接口**：读取单条候选详情，返回可补值字段（联系电话、职业类别、常住地址）。
- **前端**：基础调查页面在编辑状态根据当前姓名与证件号发起候选查询；追踪调查页面通过路由参数定位当前调查记录，路由参数不得直接当作基线记录编号。
- **补值服务**：两个模块共用，接收明确的身份键（姓名、证件号）与当前地区，不暗中读取其他页面状态。

### 1.3 兼容性要求

- 保持现有查询接口、路由和鉴权行为不变。
- 不新增数据库迁移，不修改现有表结构。
- 前端不引入新的状态管理库或浏览器库，仅描述时序保护逻辑。

---

## 二、两个模块共用的处理步骤

### 2.1 候选查询

1. 基础调查：编辑状态下，用户输入或修改姓名与证件号后，触发候选查询。
2. 追踪调查：进入编辑状态后，根据当前调查记录中已有的姓名与证件号触发候选查询；路由参数仅用于定位当前调查记录，不参与基线匹配。
3. 查询条件：姓名 + 证件号 + 当前用户所属地区。
4. 排序规则：更新时间降序；更新时间相同时按记录编号升序。
5. 返回结果：候选摘要列表（编号、地区、更新时间、姓名掩码）。

### 2.2 候选状态管理

- 用户修改姓名或证件号后，之前已加载的候选列表与详情立即失效。
- 候选请求未结束时再次修改姓名，迟到的旧请求必须被丢弃，不得覆盖当前状态。
- 实现方式：为每次查询生成请求标识（如递增序号或时间戳），响应返回时比对当前请求标识，不一致则丢弃。

### 2.3 用户确认

- 无候选时：不弹确认框，直接结束。
- 有候选时：必须弹出确认框，展示候选摘要（编号、地区、更新时间、姓名掩码），不得展示完整证件号。
- 用户取消、关闭弹窗或离开编辑页面：不得补写任何字段，原值与未保存的编辑值均保持不变。

### 2.4 详情获取与写入

1. 用户同意某条候选后，在写入表单前，调用详情接口获取该候选的最新详情。
2. 校验详情返回的编号与用户同意的候选编号一致；不一致则停止写入并提示信息不一致。
3. 按字段白名单逐字段判断是否允许补入。
4. 写入时仅修改目标值为 null 或空字符串的字段。

### 2.5 异常处理

- 查询失败或详情失败：保留原值，显示面向录入人员的失败提示，说明本次未执行补值且原数据保留；不显示内部堆栈、数据库错误文本或上游服务地址。
- 地区信息缺失或无法核验：该候选不视为地区匹配成功，不得默认补全；与“确无候选”“明确地区不符”分别处理并分别提示。

---

## 三、字段写入与异常分支伪代码

### 3.1 候选查询与状态管理

```java
// 前端伪代码（TypeScript 风格）
let currentRequestId = 0;
let candidates: CandidateSummary[] = [];
let selectedCandidate: CandidateSummary | null = null;

async function onIdentityChanged(name: string, idNumber: string) {
    currentRequestId++;
    const requestId = currentRequestId;
    candidates = [];
    selectedCandidate = null;

    if (!name || !idNumber) {
        return;
    }

    try {
        const resp = await queryCandidates({ name, idNumber, region: currentUserRegion });
        if (requestId !== currentRequestId) {
            return; // 迟到响应，丢弃
        }
        candidates = resp.list;
        if (candidates.length === 0) {
            return; // 无候选，不弹确认框
        }
        showConfirmDialog(candidates);
    } catch (e) {
        if (requestId !== currentRequestId) {
            return;
        }
        showUserFriendlyError("本次未执行补值，原数据已保留");
    }
}
```

### 3.2 用户确认与详情校验

```java
async function onCandidateConfirmed(candidate: CandidateSummary) {
    selectedCandidate = candidate;
    try {
        const detail = await fetchCandidateDetail(candidate.id);
        if (detail.id !== candidate.id) {
            showUserFriendlyError("候选信息不一致，已停止补值");
            return;
        }
        applyFields(detail);
    } catch (e) {
        showUserFriendlyError("本次未执行补值，原数据已保留");
    }
}

function onCancel() {
    selectedCandidate = null;
    // 不修改任何字段
}
```

### 3.3 字段写入

```java
// 字段白名单
const ALLOWED_FIELDS = ["contactPhone", "occupationCategory", "residenceAddress"];

function applyFields(detail: CandidateDetail) {
    const form = getCurrentForm();

    // 联系电话
    if (isEmpty(form.contactPhone) && !isEmpty(detail.contactPhone)) {
        form.contactPhone = detail.contactPhone;
    }

    // 职业类别：必须使用空值比较，不能用逻辑或
    if (isEmpty(form.occupationCategory) && !isEmpty(detail.occupationCategory)) {
        form.occupationCategory = detail.occupationCategory;
    }

    // 常住地址
    if (isEmpty(form.residenceAddress) && !isEmpty(detail.residenceAddress)) {
        form.residenceAddress = detail.residenceAddress;
    }

    // 禁止写入其他字段
}

function isEmpty(value: any): boolean {
    return value === null || value === undefined || value === "";
}
```

### 3.4 地区核验分支

```java
function evaluateCandidate(candidate: CandidateSummary, currentRegion: string): CandidateStatus {
    if (candidate.region == null || candidate.region === "") {
        return CandidateStatus.REGION_UNVERIFIABLE; // 地区缺失，不视为匹配成功
    }
    if (candidate.region !== currentRegion) {
        return CandidateStatus.REGION_MISMATCH; // 明确地区不符
    }
    return CandidateStatus.MATCHED;
}
```

### 3.5 后端查询伪代码

```java
public List<CandidateSummary> queryCandidates(String name, String idNumber, String region) {
    LambdaQueryWrapper<BaselineRecord> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(BaselineRecord::getName, name)
           .eq(BaselineRecord::getIdNumber, idNumber)
           .eq(BaselineRecord::getRegion, region)
           .orderByDesc(BaselineRecord::getUpdateTime)
           .orderByAsc(BaselineRecord::getRecordNo);
    return baselineMapper.selectList(wrapper)
            .stream()
            .map(this::toSummary)
            .collect(Collectors.toList());
}

private CandidateSummary toSummary(BaselineRecord record) {
    CandidateSummary summary = new CandidateSummary();
    summary.setId(record.getId());
    summary.setRegion(record.getRegion());
    summary.setUpdateTime(record.getUpdateTime());
    summary.setNameMasked(maskName(record.getName()));
    return summary;
}
```

---

## 四、测试清单

| 编号 | 测试场景 | 前置条件 | 操作 | 预期结果 |
|------|----------|----------|------|----------|
| T01 | 基础调查无候选 | 姓名与证件号无匹配记录 | 触发查询 | 不弹确认框，无字段变化 |
| T02 | 基础调查有候选 | 存在匹配记录 | 触发查询 | 弹出确认框，展示编号、地区、更新时间、姓名掩码 |
| T03 | 追踪调查有候选 | 存在匹配记录 | 触发查询 | 弹出确认框，路由参数不参与匹配 |
| T04 | 用户取消 | 确认框已弹出 | 点击取消 | 所有字段保持原值，未保存编辑值不清空 |
| T05 | 目标电话为空，来源有值 | 目标 contactPhone 为 null/空 | 确认补值 | contactPhone 补入来源值 |
| T06 | 目标电话已有值 | 目标 contactPhone 非空 | 确认补值 | contactPhone 不被覆盖 |
| T07 | 目标电话为空，来源也为空 | 双方均为空 | 确认补值 | 保持空值，不制造占位号码 |
| T08 | 职业类别为 0 | 目标 occupationCategory 为 0 | 确认补值 | 0 被视为有效原值，不被替换 |
| T09 | 职业类别为 false | 目标 occupationCategory 为 false | 确认补值 | false 被视为有效原值，不被替换 |
| T10 | 常住地址补值 | 目标 residenceAddress 为空，来源有值 | 确认补值 | 补入来源地址，不涉及其他地址类型 |
| T11 | 字段白名单 | 详情返回额外字段 | 确认补值 | 仅写入联系电话、职业类别、常住地址 |
| T12 | 候选排序 | 多条候选，更新时间不同 | 查询 | 按更新时间降序 |
| T13 | 候选排序（时间相同） | 多条候选，更新时间相同 | 查询 | 按记录编号升序，分页/重试顺序稳定 |
| T14 | 修改姓名后旧候选失效 | 已加载候选 | 修改姓名 | 旧候选列表与详情失效，不沿用 |
| T15 | 迟到响应丢弃 | 请求未结束 | 再次修改姓名 | 旧请求响应被丢弃，不覆盖当前状态 |
| T16 | 详情编号不一致 | 用户同意候选 A | 详情返回候选 B | 停止写入，提示信息不一致 |
| T17 | 查询失败 | 接口异常 | 触发查询 | 保留原值，显示面向录入人员的失败提示 |
| T18 | 详情失败 | 接口异常 | 确认补值 | 保留原值，显示面向录入人员的失败提示 |
| T19 | 地区缺失 | 候选 region 为 null/空 | 查询 | 不视为匹配成功，与确无候选、地区不符分别提示 |
| T20 | 地区不符 | 候选 region 与当前用户地区不同 | 查询 | 不视为可补值候选 |
| T21 | 离开编辑页面 | 确认框已弹出 | 离开页面 | 不补写字段，原值与编辑值保留 |
| T22 | 日志脱敏 | 任意查询/详情操作 | 查看日志 | 仅记录稳定错误类型和请求标识，不记录姓名或证件号原文 |
| T23 | 确认框脱敏 | 有候选 | 查看确认框 | 不展示完整证件号 |
| T24 | 追踪调查路由参数 | 路由参数为调查记录编号 | 触发查询 | 路由参数不被当作基线记录编号 |

---

## 五、待确认项

| 编号 | 待确认问题 | 影响范围 | 当前处理 |
|------|------------|----------|----------|
| Q01 | 候选地区信息缺失、无法核验当前地区条件时，该候选应如何处理？ | 候选筛选与提示逻辑 | 暂不默认补全，标记为 REGION_UNVERIFIABLE，与确无候选、地区不符分别提示；最终业务决定待确认 |

---

## 六、资料引用说明

- **正式说明**：`materials/software/current-brief.md` 中“地区匹配表单的安全补值：合成现行资料”及其“分支与边界”部分，为本方案的事实依据。
- **测试示例**：`materials/software/tests/layout-example.txt` 仅用于排版和来源识别，其中 CSV、Python、收入、准确率等内容不是本题事实，未用于本方案。
- **旧版草稿**：本方案未引用任何旧版草稿；如后续出现，应与正式说明分开标注。
- **演示字段**：目录中的演示字段不属于本次字段白名单，未纳入方案。