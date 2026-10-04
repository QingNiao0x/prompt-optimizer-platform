# 地区基线自动匹配与表单补值实现方案

## 1. 背景与现有调用关系

本方案服务于两套调查模块：**基础调查**与**追踪调查**。两者复用同一份补值服务，但入口上下文不同：

| 模块 | 身份键来源 | 地区来源 | 路由参数语义 |
|---|---|---|---|
| 基础调查 | 编辑页面当前表单中的姓名、证件号 | 当前用户所属地区 | 无基线记录路由参数 |
| 追踪调查 | 编辑页面当前表单中的姓名、证件号 | 当前用户所属地区 | 路由参数仅定位当前调查记录，**不得**作为基线记录编号 |

现有接口保持兼容：

- **候选摘要查询接口**：入参为姓名、证件号、当前用户所属地区；返回候选摘要列表（编号、地区、更新时间、姓名掩码），按更新时间降序、编号升序排列。
- **单条详情接口**：入参为候选编号；返回该候选的补值字段（联系电话、职业类别、常住地址）。
- **写入路径**：用户确认后，前端调用详情接口获取最新详情，再写入当前表单的对应字段。

调用时序：

```
基础调查/追踪调查编辑页
        │
        │ 姓名 + 证件号 + 当前地区
        ▼
候选摘要查询接口 ──► 候选列表（有序）
        │
        │ 用户选择/确认某候选
        ▼
单条详情接口（按候选编号）──► 补值字段
        │
        │ 字段级空值判断后写入
        ▼
当前表单（仅白名单字段）
```

---

## 2. 两个模块共用的处理步骤

补值服务不感知调用方是基础调查还是追踪调查。调用方必须显式传入：**姓名、证件号、当前用户所属地区**。服务内部不读取任何页面全局状态或路由参数。

### 步骤一：候选查询

```
输入：name, idNumber, currentRegion
输出：候选摘要列表（有序）或空列表
```

- 查询条件：`name = ? AND id_number = ? AND region = currentRegion`
- 排序：`updated_at DESC, record_id ASC`
- 返回字段仅含：`recordId`、`region`、`updatedAt`、`nameMasked`
- **地区缺失或无法核验的记录不进入候选列表**，与"确无候选"分开处理（见异常分支）

### 步骤二：用户确认

- 候选列表为空 → 不弹确认框，不执行任何写入
- 候选列表非空 → 弹出确认框，展示候选摘要（姓名掩码，**不展示完整证件号**）
- 用户取消/关闭弹窗 → 全部字段保持原值不变，不发起详情请求

### 步骤三：获取最新详情

- 仅在用户确认后发起
- 请求参数为**用户确认的那条候选的编号**
- 响应中必须校验返回的编号与请求编号一致；不一致则停止写入并提示"信息不一致"

### 步骤四：字段级补值

- 仅对白名单字段执行：`联系电话`、`职业类别`、`常住地址`
- 对每个字段独立判断：目标值为 `null` 或空字符串 `""` 时才允许写入来源值
- `0`、`false`、非空字符串均为有效原值，**必须保留，不得覆盖**

---

## 3. 字段写入与异常分支伪代码

### 3.1 前端补值服务（TypeScript 伪代码）

```typescript
// 白名单常量
const ALLOWED_FIELDS = ['contactPhone', 'occupationCategory', 'residenceAddress'] as const;
type AllowedField = typeof ALLOWED_FIELDS[number];

interface CandidateSummary {
  recordId: string;
  region: string;
  updatedAt: string;
  nameMasked: string;
}

interface CandidateDetail {
  recordId: string;
  contactPhone: string | null;
  occupationCategory: number | null;
  residenceAddress: string | null;
}

interface FillRequest {
  name: string;
  idNumber: string;
  currentRegion: string;
}

// 请求序号，用于时序保护
let requestSequence = 0;

async function queryCandidates(req: FillRequest): Promise<CandidateSummary[]> {
  const seq = ++requestSequence;
  const result = await api.queryCandidateSummaries(req);
  // 迟到请求失效
  if (seq !== requestSequence) {
    throw new StaleRequestError();
  }
  return result;
}

async function fillFromCandidate(
  req: FillRequest,
  confirmedCandidate: CandidateSummary,
  currentForm: Record<string, unknown>
): Promise<Record<string, unknown>> {
  // 1. 获取最新详情
  const detail: CandidateDetail = await api.getCandidateDetail(confirmedCandidate.recordId);

  // 2. 编号一致性校验
  if (detail.recordId !== confirmedCandidate.recordId) {
    throw new CandidateMismatchError('候选信息不一致，已停止补值');
  }

  // 3. 字段级补值
  const nextForm = { ...currentForm };
  for (const field of ALLOWED_FIELDS) {
    const targetValue = currentForm[field];
    const sourceValue = detail[field];

    if (isNullOrEmptyString(targetValue) && !isNullOrEmptyString(sourceValue)) {
      nextForm[field] = sourceValue;
    }
    // 其他情况：保持原值不变
  }

  return nextForm;
}

function isNullOrEmptyString(value: unknown): boolean {
  return value === null || value === undefined || value === '';
}
```

### 3.2 后端查询伪代码（Java / MyBatis-Plus）

```java
// 候选摘要查询
public List<CandidateSummaryVO> queryCandidates(String name, String idNumber, String currentRegion) {
    return candidateMapper.selectList(
        new LambdaQueryWrapper<CandidateRecord>()
            .eq(CandidateRecord::getName, name)
            .eq(CandidateRecord::getIdNumber, idNumber)
            .eq(CandidateRecord::getRegion, currentRegion)
            .orderByDesc(CandidateRecord::getUpdatedAt)
            .orderByAsc(CandidateRecord::getRecordId)
    ).stream()
     .map(this::toSummaryVO)  // 仅映射 recordId, region, updatedAt, nameMasked
     .toList();
}

// 单条详情查询
public CandidateDetailVO getCandidateDetail(String recordId) {
    CandidateRecord record = candidateMapper.selectById(recordId);
    if (record == null) {
        throw new CandidateNotFoundException("候选记录不存在");
    }
    return toDetailVO(record);  // 仅映射 recordId, contactPhone, occupationCategory, residenceAddress
}
```

### 3.3 异常分支处理

| 场景 | 处理方式 | 用户提示 |
|---|---|---|
| 候选列表为空 | 不弹确认框，不写入 | 无需提示（或轻提示"无匹配候选"） |
| 候选地区缺失/无法核验 | 该记录**不进入候选列表**；与"确无候选"在日志中区分 | 同"无匹配候选"，但日志记录 `region_unverifiable` |
| 候选查询网络失败 | 保留原值，不弹确认框 | "本次未执行补值，原数据已保留，请稍后重试" |
| 详情接口网络失败 | 保留原值 | "本次未执行补值，原数据已保留，请稍后重试" |
| 详情返回编号与请求不一致 | 停止写入 | "候选信息不一致，已停止补值" |
| 用户取消/关闭弹窗 | 不发起详情请求，不写入 | 无 |
| 用户修改姓名或证件号 | 旧请求失效（时序保护），不弹旧候选确认框 | 无 |
| 迟到旧请求返回 | 丢弃结果，不弹确认框，不写入 | 无 |

### 3.4 日志规范

- 只记录稳定错误类型（如 `candidate_query_failed`、`detail_mismatch`、`region_unverifiable`）和请求标识（requestId）
- **不记录**姓名、证件号原文或掩码
- 不记录内部堆栈、数据库错误文本、上游服务地址

---

## 4. 测试清单（按业务行为组织）

### 4.1 候选查询与排序

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| Q-01 | 姓名+证件号+地区均匹配 | 存在 2 条匹配记录，更新时间不同 | 返回 2 条，按更新时间降序 |
| Q-02 | 更新时间相同的排序稳定性 | 2 条记录 updated_at 相同，编号不同 | 按编号升序，多次请求顺序一致 |
| Q-03 | 地区不匹配 | 记录存在但地区 ≠ 当前用户地区 | 不返回该记录 |
| Q-04 | 地区字段为 NULL | 记录存在但地区为 NULL | 不返回该记录，日志标记 `region_unverifiable` |
| Q-05 | 地区字段为空字符串 | 记录存在但地区为 `""` | 不返回该记录，日志标记 `region_unverifiable` |
| Q-06 | 姓名或证件号不匹配 | 仅一个键匹配 | 不返回该记录 |
| Q-07 | 无任何匹配 | 无记录满足条件 | 返回空列表，不弹确认框 |
| Q-08 | 查询接口失败 | 模拟网络错误 | 保留原值，提示"本次未执行补值" |

### 4.2 确认框行为

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| C-01 | 有候选时弹确认框 | 候选列表非空 | 弹出确认框，展示姓名掩码 |
| C-02 | 确认框不展示完整证件号 | 候选列表非空 | 界面无完整证件号明文 |
| C-03 | 用户点击取消 | 确认框已弹出 | 不发起详情请求，全部字段原值不变 |
| C-04 | 用户关闭弹窗 | 确认框已弹出 | 不发起详情请求，全部字段原值不变 |
| C-05 | 无候选时不弹确认框 | 候选列表为空 | 无确认框，无写入 |

### 4.3 详情获取与一致性

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| D-01 | 详情返回编号与请求一致 | 正常详情响应 | 继续执行补值 |
| D-02 | 详情返回编号与请求不一致 | 模拟响应编号不同 | 停止写入，提示"候选信息不一致" |
| D-03 | 详情接口失败 | 模拟网络错误 | 保留原值，提示"本次未执行补值" |
| D-04 | 详情记录不存在 | 候选编号无效 | 保留原值，提示"本次未执行补值" |

### 4.4 字段级补值规则

| 编号 | 测试场景 | 目标值 | 来源值 | 预期结果 |
|---|---|---|---|---|
| F-01 | 目标为空字符串，来源有值 | `""` | `"13800001111"` | 写入来源值 |
| F-02 | 目标为 null，来源有值 | `null` | `"13800001111"` | 写入来源值 |
| F-03 | 目标有非空值，来源更新 | `"13900002222"` | `"13800001111"` | 保留目标原值 |
| F-04 | 目标为空，来源也为空 | `""` | `""` | 保持空值，不制造占位 |
| F-05 | 目标为空，来源为 null | `null` | `null` | 保持 null |
| F-06 | 职业类别目标为 0 | `0` | `3` | 保留 `0`，不触发替换 |
| F-07 | 职业类别目标为 false | `false` | `true` | 保留 `false` |
| F-08 | 职业类别目标为空字符串 | `""` | `0` | 写入 `0`（来源 0 是有效值） |
| F-09 | 常住地址目标为空 | `""` | `"某市某区某路 1 号"` | 写入来源值 |
| F-10 | 非白名单字段 | 任意 | 任意 | 不写入，保持原值 |
| F-11 | 多字段混合 | 电话有值、职业为空、地址为空 | 电话有值、职业有值、地址有值 | 仅职业和地址被补入，电话保留 |

### 4.5 时序保护与身份变更

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| T-01 | 查询未返回时修改姓名 | 发起查询后立即修改姓名 | 旧请求结果失效，不弹确认框 |
| T-02 | 查询未返回时修改证件号 | 发起查询后立即修改证件号 | 旧请求结果失效，不弹确认框 |
| T-03 | 基础调查：修改键后旧候选失效 | 已有候选列表，修改姓名 | 旧候选列表清空/失效，不沿用旧身份详情 |
| T-04 | 追踪调查：路由参数不作为基线编号 | 路由参数指向某调查记录 | 补值服务不读取路由参数作为候选编号 |
| T-05 | 追踪调查：显式传入身份键与地区 | 追踪调查页面 | 补值服务使用传入的姓名、证件号、当前地区 |

### 4.6 模块兼容性

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| M-01 | 基础调查完整补值流程 | 编辑页有姓名+证件号，有匹配候选 | 确认后正确补入白名单字段 |
| M-02 | 追踪调查完整补值流程 | 编辑页有姓名+证件号，有匹配候选 | 确认后正确补入白名单字段 |
| M-03 | 现有查询接口兼容 | 原有调用方 | 接口签名、返回结构不变 |
| M-04 | 现有路由兼容 | 原有页面路由 | 路由行为不变 |
| M-05 | 现有鉴权兼容 | 原有鉴权逻辑 | 鉴权行为不变 |

### 4.7 日志与安全

| 编号 | 测试场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| L-01 | 查询失败日志 | 模拟查询异常 | 日志含稳定错误类型和请求标识，不含姓名/证件号原文 |
| L-02 | 详情不一致日志 | 模拟编号不一致 | 日志含 `detail_mismatch`，不含敏感信息 |
| L-03 | 地区不可核验日志 | 地区为 NULL 的记录 | 日志含 `region_unverifiable`，与"确无候选"区分 |
| L-04 | 正常操作日志 | 正常补值 | 日志不含姓名、证件号、电话、地址原文 |

---

## 5. 待确认项

仅保留一项真正影响任务目标且资料未覆盖的业务决定：

| 待确认项 | 影响范围 | 当前方案的处理 |
|---|---|---|
| 候选地区信息缺失或无法核验时，是否需要在用户界面给出与"确无候选"不同的提示？ | 仅影响用户提示文案，不影响匹配与写入逻辑 | 当前方案：日志中区分（`region_unverifiable`），用户界面与"确无候选"相同，不弹确认框、不写入 |