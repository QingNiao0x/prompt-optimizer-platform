# 地区基线自动匹配与表单补值实现方案

## 1. 背景与现有调用关系

本方案覆盖两个调查模块：**基础调查**与**追踪调查**。两者复用同一份补值服务，但入口与身份键来源不同。

| 模块 | 身份键来源 | 地区来源 | 现有行为约束 |
|---|---|---|---|
| 基础调查 | 编辑页面当前表单中的姓名、证件号 | 当前用户所属地区 | 进入编辑状态时可发起候选查询；修改姓名或证件号后旧候选失效 |
| 追踪调查 | 由页面显式传入的姓名、证件号（不得从路由参数推导） | 当前用户所属地区 | 路由参数仅定位调查记录，不得当作基线记录编号 |

**现有调用关系：**

```
前端（Vue 3 + TypeScript）
  ├── 基础调查编辑页 ──► 候选查询接口（摘要）──► 用户确认 ──► 详情接口 ──► 补值写入
  └── 追踪调查编辑页 ──► 候选查询接口（摘要）──► 用户确认 ──► 详情接口 ──► 补值写入
                              │
                              ▼
后端（Java 21 / Spring Boot 3 / MyBatis-Plus / PostgreSQL）
  └── 补值服务（共用）
        ├── 候选查询：按姓名+证件号+当前地区匹配，返回摘要列表
        ├── 候选详情：按记录编号读取补值字段
        └── 补值写入：按白名单字段、空值规则写入表单
```

**兼容性要求：** 现有查询接口、路由和鉴权行为保持不变。本方案不新增路由、不修改鉴权逻辑，仅在现有接口内部补充匹配与补值逻辑。

---

## 2. 两个模块共用的处理步骤

### 2.1 候选查询阶段

1. 前端收集当前表单中的**姓名**与**证件号**，连同**当前用户所属地区**一并传入查询接口。
2. 后端执行查询：
   - 条件一：姓名与证件号均匹配；
   - 条件二：记录的地区与当前用户所属地区一致。
   - 两个条件同时满足才纳入候选。
3. 排序规则：按**更新时间降序**，时间相同按**记录编号升序**。
4. 返回候选摘要列表，每条摘要包含：**记录编号、地区、更新时间、姓名掩码**。不得返回完整证件号。
5. 前端收到摘要后：
   - 无候选：不弹确认框，保持原值不变。
   - 有候选：弹出确认框，展示摘要信息（不含完整证件号）。

### 2.2 用户确认与详情获取阶段

1. 用户点击确认后，前端携带所选候选的**记录编号**调用详情接口。
2. 后端返回该记录的补值字段：**联系电话、职业类别、常住地址**。
3. 前端校验详情接口返回的记录编号与用户所选编号一致；不一致则停止写入并提示"信息不一致"。

### 2.3 补值写入阶段

1. 对三个白名单字段逐一执行空值比较：
   - 目标值为 `null` 或空字符串 `""` 时，允许写入来源值；
   - 目标值为 `0`、`false` 或非空字符串时，保留原值，不覆盖。
2. 写入完成后，表单中仅白名单字段可能发生变化，其余字段不受影响。

### 2.4 取消与异常分支

| 场景 | 行为 |
|---|---|
| 用户点击取消 | 全部字段保持原值不变 |
| 用户关闭弹窗 | 全部字段保持原值不变 |
| 用户离开编辑页面 | 不执行补写；已保存原值与未保存编辑值均不被清空 |
| 查询接口失败 | 保留原值，提示"本次未执行补值，原数据保留" |
| 详情接口失败 | 保留原值，提示"本次未执行补值，原数据保留" |
| 详情返回编号不一致 | 停止写入，提示"信息不一致" |

---

## 3. 字段写入与异常分支伪代码

### 3.1 后端补值服务伪代码

```java
// 候选查询
public List<CandidateSummary> queryCandidates(String name, String idNumber, String currentRegion) {
    // 条件：姓名匹配 AND 证件号匹配 AND 地区匹配
    List<Candidate> candidates = candidateMapper.selectByKeysAndRegion(name, idNumber, currentRegion);
    
    // 排序：更新时间降序，记录编号升序
    candidates.sort(Comparator
        .comparing(Candidate::getUpdatedAt).reversed()
        .thenComparing(Candidate::getRecordId));
    
    // 返回摘要（不含完整证件号）
    return candidates.stream()
        .map(c -> new CandidateSummary(c.getRecordId(), c.getRegion(), c.getUpdatedAt(), maskName(c.getName())))
        .toList();
}

// 候选详情
public CandidateDetail getCandidateDetail(String recordId) {
    return candidateMapper.selectDetailById(recordId); // 仅返回白名单字段
}

// 补值写入
public void applyFill(String recordId, FormData targetForm) {
    CandidateDetail source = getCandidateDetail(recordId);
    
    // 编号一致性校验
    if (!source.getRecordId().equals(recordId)) {
        throw new InconsistentRecordException("候选记录编号不一致");
    }
    
    // 白名单字段逐一处理
    if (isNullOrEmpty(targetForm.getPhone())) {
        targetForm.setPhone(source.getPhone());
    }
    if (isNullOrEmpty(targetForm.getOccupationCategory())) {
        targetForm.setOccupationCategory(source.getOccupationCategory());
    }
    if (isNullOrEmpty(targetForm.getResidentialAddress())) {
        targetForm.setResidentialAddress(source.getResidentialAddress());
    }
    // 其他字段不写入
}

private boolean isNullOrEmpty(Object value) {
    return value == null || (value instanceof String s && s.isEmpty());
}
```

### 3.2 前端时序保护伪代码（TypeScript）

```typescript
// 请求序号保护：每次发起新查询时递增序号，响应返回时校验序号
let querySequence = 0;

async function queryCandidates(name: string, idNumber: string) {
    const seq = ++querySequence;
    const result = await api.queryCandidates({ name, idNumber, region: currentUser.region });
    if (seq !== querySequence) {
        // 迟到的旧请求，丢弃
        return;
    }
    // 处理结果
    if (result.candidates.length === 0) {
        // 无候选，不弹框
        return;
    }
    showConfirmDialog(result.candidates);
}

// 用户修改姓名或证件号时
function onIdentityKeyChange() {
    querySequence++; // 使所有进行中的旧请求失效
    clearCandidates();
}
```

### 3.3 前端补值确认伪代码

```typescript
async function onUserConfirm(selectedCandidate: CandidateSummary) {
    try {
        const detail = await api.getCandidateDetail(selectedCandidate.recordId);
        if (detail.recordId !== selectedCandidate.recordId) {
            showError("候选信息不一致，本次未执行补值");
            return;
        }
        applyFillToForm(detail);
    } catch (e) {
        showError("本次未执行补值，原数据保留");
    }
}

function applyFillToForm(detail: CandidateDetail) {
    // 空值比较，不使用逻辑或
    if (form.phone === null || form.phone === "") {
        form.phone = detail.phone;
    }
    if (form.occupationCategory === null || form.occupationCategory === "") {
        form.occupationCategory = detail.occupationCategory;
    }
    if (form.residentialAddress === null || form.residentialAddress === "") {
        form.residentialAddress = detail.residentialAddress;
    }
}
```

---

## 4. 按业务行为组织的测试表

### 4.1 候选查询与匹配

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| Q-01 | 姓名与证件号均匹配，地区匹配 | 存在一条记录，地区与当前用户一致 | 返回该候选摘要 |
| Q-02 | 姓名匹配，证件号不匹配 | 存在一条记录，证件号不同 | 不返回该候选 |
| Q-03 | 证件号匹配，姓名不匹配 | 存在一条记录，姓名不同 | 不返回该候选 |
| Q-04 | 姓名与证件号均匹配，地区不匹配 | 记录地区与当前用户地区不同 | 不返回该候选 |
| Q-05 | 姓名与证件号均匹配，地区缺失 | 记录地区为 null 或空 | 不返回该候选（无法核验地区，不视为匹配） |
| Q-06 | 无任何匹配记录 | 数据库中无对应记录 | 返回空列表，前端不弹确认框 |
| Q-07 | 多条候选排序 | 两条候选更新时间相同 | 按记录编号升序排列，顺序稳定 |
| Q-08 | 多条候选排序 | 更新时间不同 | 按更新时间降序排列 |
| Q-09 | 分页/重试后顺序稳定 | 同一查询条件重复请求 | 返回顺序一致 |

### 4.2 摘要与隐私

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| P-01 | 摘要字段完整性 | 有候选返回 | 包含编号、地区、更新时间、姓名掩码 |
| P-02 | 证件号不泄露 | 有候选返回 | 摘要中不含完整证件号 |
| P-03 | 确认框展示 | 有候选弹出确认框 | 不展示完整证件号 |
| P-04 | 日志脱敏 | 查询或详情发生错误 | 日志不含姓名或证件号原文，仅含错误类型与请求标识 |

### 4.3 补值写入规则

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| W-01 | 目标电话为空，来源有值 | 目标 `null`，来源 `"13800000000"` | 补入来源值 |
| W-02 | 目标电话为空字符串，来源有值 | 目标 `""`，来源 `"13800000000"` | 补入来源值 |
| W-03 | 目标电话已有值 | 目标 `"13900000000"`，来源 `"13800000000"` | 保留原值，不覆盖 |
| W-04 | 目标电话为空，来源也为空 | 目标 `null`，来源 `null` | 保持空值，不制造占位号码 |
| W-05 | 职业类别为 0 | 目标 `0`，来源 `5` | 保留原值 0，不触发替换 |
| W-06 | 职业类别为 null | 目标 `null`，来源 `5` | 补入来源值 5 |
| W-07 | 职业类别为 false | 目标 `false`，来源 `5` | 保留原值 false |
| W-08 | 常住地址为空，来源有值 | 目标 `null`，来源 `"某市某区"` | 补入来源值 |
| W-09 | 常住地址已有值 | 目标 `"原地址"`，来源 `"新地址"` | 保留原值 |
| W-10 | 非白名单字段 | 目标表单含其他字段 | 不写入任何非白名单字段 |
| W-11 | 地址字段边界 | 来源含户籍地址、单位地址等 | 不写入，仅处理常住地址单字段 |

### 4.4 详情一致性与异常

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| E-01 | 详情编号与所选一致 | 用户确认候选 A，详情返回 A | 正常写入 |
| E-02 | 详情编号与所选不一致 | 用户确认候选 A，详情返回 B | 停止写入，提示"信息不一致" |
| E-03 | 详情接口失败 | 网络异常或服务端错误 | 保留原值，提示"本次未执行补值，原数据保留" |
| E-04 | 查询接口失败 | 网络异常或服务端错误 | 保留原值，提示"本次未执行补值，原数据保留"，不伪装为无候选 |
| E-05 | 错误提示内容 | 任意失败场景 | 不含内部堆栈、数据库错误文本、上游服务地址 |

### 4.5 取消与页面行为

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| C-01 | 用户点击取消 | 已弹出确认框 | 全部字段保持原值不变 |
| C-02 | 用户关闭弹窗 | 已弹出确认框 | 全部字段保持原值不变 |
| C-03 | 用户离开编辑页面 | 有未确认候选或未完成补值 | 不执行补写；已保存原值与未保存编辑值均不被清空 |
| C-04 | 修改姓名后旧候选失效 | 候选查询已返回，用户修改姓名 | 旧候选不再可用，不得沿用旧身份详情 |
| C-05 | 修改证件号后旧候选失效 | 候选查询已返回，用户修改证件号 | 旧候选不再可用 |
| C-06 | 迟到旧请求失效 | 查询 A 未返回时修改姓名并触发查询 B，A 后返回 | A 的结果被丢弃，不覆盖 B 的结果 |

### 4.6 模块兼容性

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| M-01 | 基础调查模块 | 进入编辑状态，有姓名与证件号 | 可发起候选查询，流程正常 |
| M-02 | 追踪调查模块 | 路由参数仅定位调查记录 | 身份键由页面显式传入，不从路由参数推导 |
| M-03 | 追踪调查模块 | 复用补值服务 | 不读取其他页面状态，不暗中使用路由参数作为基线编号 |
| M-04 | 现有接口兼容 | 原有查询接口、路由、鉴权 | 行为不变，无新增路由或鉴权变更 |

---

## 5. 待确认事项

| 事项 | 影响范围 | 当前处理 |
|---|---|---|
| 候选记录地区信息缺失时如何处理 | 候选查询阶段 | 暂不纳入候选，不默认补全；与"确无候选"和"明确地区不符"分别说明 |

---

## 6. 材料核对说明

| 材料 | 采用内容 | 排除内容 |
|---|---|---|
| `materials/software/current-brief.md` | 全部业务规则（匹配条件、白名单、排序、空值规则、异常分支、模块差异、时序保护） | 无 |
| `materials/software/tests/layout-example.txt` | 无（仅用于排版和来源识别的测试例子） | 全部内容（输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99% 等均为测试占位，非本题事实） |

**冲突与旧写法：** 未发现材料间冲突。`layout-example.txt` 中的数字与工具偏好不进入事实清单。