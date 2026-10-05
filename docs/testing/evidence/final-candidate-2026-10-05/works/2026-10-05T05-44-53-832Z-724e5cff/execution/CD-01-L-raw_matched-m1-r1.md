# 地区基线自动匹配与表单补值实现方案

## 1. 背景与现有调用关系

### 1.1 系统现状

| 模块 | 入口 | 现有行为 |
|---|---|---|
| 基础调查 | 编辑页面进入编辑状态 | 页面持有当前表单的姓名、证件号、所属地区 |
| 追踪调查 | 路由参数定位调查记录 | 路由参数仅用于加载当前调查记录，不承载基线记录身份 |
| 补值服务 | 两个模块复用同一服务 | 当前服务未实现自动匹配与补值，本次新增能力 |

### 1.2 目标调用关系

```
基础调查编辑页 ──┐
                 ├──> 候选查询接口（姓名 + 证件号 + 当前地区）──> 候选摘要列表
追踪调查编辑页 ──┘                                              │
                                                              用户确认
                                                                 │
                                          详情接口（候选编号）──> 补值字段
                                                                 │
                                                      写入表单（仅白名单字段）
```

两个模块均通过同一补值服务发起请求，服务接收**显式传入**的身份键（姓名、证件号）与当前地区，不读取任何页面全局状态或路由参数作为身份依据。

### 1.3 复用边界

- 追踪调查页面的路由参数只用于加载当前调查记录本身，**不得**被补值服务当作基线记录编号或身份键来源。
- 补值服务的入参必须由调用方（两个页面）显式提供：`name`、`credentialNo`、`region`。
- 两个模块的查询接口、路由、鉴权行为保持不变，本次不新增路由、不修改鉴权逻辑。

---

## 2. 两个模块共用的处理步骤

### 2.1 整体流程

```
步骤1：触发候选查询
  条件：编辑状态下，姓名与证件号均非空
  入参：name, credentialNo, region
  时序保护：生成请求序号，仅接受最新序号的响应

步骤2：处理查询结果
  ├─ 无候选（空列表）→ 不弹确认框，结束
  ├─ 查询失败 → 提示"本次未执行补值，原数据保留"，结束
  └─ 有候选 → 展示确认框（摘要信息）

步骤3：用户确认
  ├─ 用户取消/关闭弹窗/离开页面 → 不写入任何字段，结束
  └─ 用户同意 → 进入步骤4

步骤4：获取最新详情
  入参：用户确认的候选编号
  校验：响应中的记录编号必须与用户确认的编号一致
  ├─ 不一致 → 停止写入，提示"信息不一致，本次未执行补值"
  ├─ 详情获取失败 → 提示"本次未执行补值，原数据保留"，结束
  └─ 一致 → 进入步骤5

步骤5：字段补值
  仅处理白名单字段：联系电话、职业类别、常住地址
  对每个字段独立判断：目标值为 null 或空字符串时才写入来源值
  写入完成后结束
```

### 2.2 候选排序规则

候选摘要列表按以下规则排序：

1. 更新时间降序（最新的在前）
2. 更新时间相同时，按记录编号升序

排序必须在后端查询中完成（`ORDER BY update_time DESC, record_id ASC`），保证分页加载和重试后顺序稳定。

### 2.3 地区条件处理

| 候选记录地区状态 | 处理方式 |
|---|---|
| 地区与当前用户所属地区一致 | 属于可补值候选 |
| 地区明确不一致 | 排除，不返回 |
| 地区信息缺失（null 或空） | **暂不确定，保留为未决前提**；不得默认视为匹配成功，也不得默认排除。实施前需确认处理策略 |

> **未决前提**：候选记录地区信息缺失时的处理策略尚未确定。在确认之前，该分支不得绕过地区匹配条件。此未决项影响候选查询的过滤逻辑和候选摘要的展示逻辑。

---

## 3. 字段写入与异常分支伪代码

### 3.1 后端：候选查询

```java
// CandidateQueryService.java
public List<CandidateSummary> queryCandidates(CandidateQueryRequest request) {
    // request: { name, credentialNo, region }
    // 地区条件：region 必须匹配；地区缺失的候选处理策略待确认（见未决前提）
    return mapper.selectCandidates(request.name(), request.credentialNo(), request.region());
}
```

```sql
-- CandidateMapper.xml
SELECT record_id, region, update_time, masked_name
FROM baseline_record
WHERE name = #{name}
  AND credential_no = #{credentialNo}
  AND region = #{region}   -- 地区缺失记录的处理策略待确认
ORDER BY update_time DESC, record_id ASC
```

### 3.2 后端：详情获取

```java
// CandidateDetailService.java
public CandidateDetail getDetail(String recordId) {
    return mapper.selectDetailById(recordId);
    // 返回：recordId, phone, occupationCategory, residentialAddress
    // 仅返回白名单字段，不返回其他字段
}
```

### 3.3 前端：补值服务（两个模块共用）

```typescript
// useBaselineAutoFill.ts
interface AutoFillParams {
  name: string;
  credentialNo: string;
  region: string;
  targetForm: {
    phone: string | null;
    occupationCategory: number | null;
    residentialAddress: string | null;
  };
}

interface CandidateSummary {
  recordId: string;
  region: string;
  updateTime: string;
  maskedName: string; // 姓名掩码，不含完整证件号
}

interface CandidateDetail {
  recordId: string;
  phone: string | null;
  occupationCategory: number | null;
  residentialAddress: string | null;
}

// 时序保护：请求序号
let latestRequestSeq = 0;

async function queryCandidates(params: AutoFillParams): Promise<CandidateSummary[]> {
  const seq = ++latestRequestSeq;
  try {
    const response = await api.queryCandidates({
      name: params.name,
      credentialNo: params.credentialNo,
      region: params.region,
    });
    // 迟到响应失效
    if (seq !== latestRequestSeq) {
      return []; // 或抛出静默取消
    }
    return response.data;
  } catch (error) {
    if (seq !== latestRequestSeq) {
      return []; // 旧请求失败不提示
    }
    // 面向录入人员的失败提示
    showUserFriendlyError("本次未执行补值，原数据保留。请稍后重试。");
    return [];
  }
}

async function fetchDetail(recordId: string): Promise<CandidateDetail | null> {
  try {
    const response = await api.getCandidateDetail(recordId);
    // 编号一致性校验
    if (response.data.recordId !== recordId) {
      showUserFriendlyError("信息不一致，本次未执行补值。");
      return null;
    }
    return response.data;
  } catch (error) {
    showUserFriendlyError("本次未执行补值，原数据保留。请稍后重试。");
    return null;
  }
}

function applyAutoFill(
  target: AutoFillParams["targetForm"],
  source: CandidateDetail
): void {
  // 仅白名单字段，逐字段独立判断
  if (isEmptyValue(target.phone) && !isEmptyValue(source.phone)) {
    target.phone = source.phone;
  }
  if (isEmptyValue(target.occupationCategory) && !isEmptyValue(source.occupationCategory)) {
    target.occupationCategory = source.occupationCategory;
  }
  if (isEmptyValue(target.residentialAddress) && !isEmptyValue(source.residentialAddress)) {
    target.residentialAddress = source.residentialAddress;
  }
}

function isEmptyValue(value: unknown): boolean {
  // 仅 null 和空字符串视为空值
  // 0、false、非空字符串均为有效值
  return value === null || value === "";
}
```

### 3.4 前端：确认框交互

```typescript
async function handleAutoFill(params: AutoFillParams): Promise<void> {
  const candidates = await queryCandidates(params);

  if (candidates.length === 0) {
    return; // 无候选，不弹确认框
  }

  // 有候选：展示确认框
  // 确认框内容：编号、地区、更新时间、姓名掩码
  // 不得展示完整证件号
  const confirmed = await showConfirmDialog(candidates);

  if (!confirmed) {
    return; // 用户取消/关闭弹窗：不写入任何字段
  }

  const detail = await fetchDetail(confirmed.recordId);
  if (detail === null) {
    return; // 详情获取失败或编号不一致：不写入
  }

  applyAutoFill(params.targetForm, detail);
}
```

### 3.5 前端：身份键变更时的失效处理

```typescript
// 基础调查编辑页
watch(
  () => [form.name, form.credentialNo],
  () => {
    // 用户修改姓名或证件号：使之前的候选失效
    latestRequestSeq++; // 递增序号，使所有在途请求失效
    clearCandidateState(); // 清空已展示的候选列表和确认框
  }
);
```

### 3.6 前端：离开页面时的处理

```typescript
// 离开编辑页面（路由切换、组件卸载）
onBeforeUnmount(() => {
  latestRequestSeq++; // 使在途请求失效
  // 不执行任何补值写入
  // 不清空表单中已保存的原值和当前未保存的编辑值
});
```

---

## 4. 按业务行为组织的测试表

### 4.1 候选查询与排序

| 测试编号 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| Q-01 | 姓名与证件号均匹配，地区一致 | 存在匹配记录 | 返回候选摘要列表 |
| Q-02 | 姓名匹配但证件号不匹配 | — | 不返回该候选 |
| Q-03 | 证件号匹配但姓名不匹配 | — | 不返回该候选 |
| Q-04 | 姓名与证件号均匹配但地区不一致 | — | 不返回该候选 |
| Q-05 | 姓名与证件号均匹配，地区缺失 | — | **待确认**（未决前提） |
| Q-06 | 多条候选，更新时间不同 | — | 按更新时间降序排列 |
| Q-07 | 多条候选，更新时间相同 | — | 按记录编号升序排列 |
| Q-08 | 分页加载后重试 | 同一查询条件 | 排序结果完全一致 |
| Q-09 | 无任何匹配候选 | — | 返回空列表，不弹确认框 |
| Q-10 | 查询接口网络失败 | — | 提示"本次未执行补值，原数据保留"，不弹确认框 |

### 4.2 时序保护

| 测试编号 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| T-01 | 用户修改姓名后旧请求才返回 | 旧请求在途时修改姓名 | 旧请求结果被丢弃，不展示候选 |
| T-02 | 用户修改证件号后旧请求才返回 | 旧请求在途时修改证件号 | 旧请求结果被丢弃，不展示候选 |
| T-03 | 用户离开页面后请求返回 | 请求在途时离开页面 | 结果被丢弃，不弹确认框，不写入 |
| T-04 | 连续两次查询，第一次失败第二次成功 | 第一次请求失败，第二次成功 | 仅展示第二次结果 |

### 4.3 确认框行为

| 测试编号 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| C-01 | 有候选时展示确认框 | 查询返回至少一条候选 | 展示确认框 |
| C-02 | 确认框内容 | — | 包含编号、地区、更新时间、姓名掩码；不含完整证件号 |
| C-03 | 用户点击取消 | 确认框展示中 | 不写入任何字段，所有表单字段保持原值 |
| C-04 | 用户关闭弹窗 | 确认框展示中 | 不写入任何字段，所有表单字段保持原值 |
| C-05 | 用户离开编辑页面 | 确认框展示中 | 不写入任何字段，已保存原值和未保存编辑值均不被清空 |

### 4.4 详情获取与编号一致性

| 测试编号 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| D-01 | 详情接口返回的编号与用户确认的编号一致 | — | 正常获取详情，进入补值 |
| D-02 | 详情接口返回的编号与用户确认的编号不一致 | — | 停止写入，提示"信息不一致，本次未执行补值" |
| D-03 | 详情接口网络失败 | — | 提示"本次未执行补值，原数据保留"，不写入 |
| D-04 | 详情接口返回部分字段相似但编号不同 | — | 停止写入，不接受该响应 |

### 4.5 字段补值规则

| 测试编号 | 字段 | 目标值 | 来源值 | 预期结果 |
|---|---|---|---|---|
| F-01 | 联系电话 | null | "13800000000" | 补入来源值 |
| F-02 | 联系电话 | "" | "13800000000" | 补入来源值 |
| F-03 | 联系电话 | "13900000000" | "13800000000" | 保持原值，不覆盖 |
| F-04 | 联系电话 | null | null | 保持 null，不制造占位号码 |
| F-05 | 联系电话 | "" | "" | 保持空字符串 |
| F-06 | 职业类别 | null | 0 | 补入 0（0 是有效来源值） |
| F-07 | 职业类别 | 0 | 5 | 保持 0，不覆盖（0 是有效原值） |
| F-08 | 职业类别 | "" | 3 | 补入 3 |
| F-09 | 职业类别 | null | null | 保持 null |
| F-10 | 常住地址 | null | "某市某区某街道" | 补入来源值 |
| F-11 | 常住地址 | "已有地址" | "新地址" | 保持原值，不覆盖 |
| F-12 | 常住地址 | "" | "" | 保持空字符串 |
| F-13 | 非白名单字段（如户籍地址） | null | 有值 | 不写入，即使来源数据中存在该字段 |

### 4.6 模块复用

| 测试编号 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| M-01 | 基础调查页触发补值 | 编辑状态，姓名与证件号非空 | 正常发起查询 |
| M-02 | 追踪调查页触发补值 | 编辑状态，显式传入姓名、证件号、地区 | 正常发起查询 |
| M-03 | 追踪调查页路由参数被误用为基线编号 | 路由参数存在 | 补值服务不读取路由参数，仅使用显式入参 |
| M-04 | 两个模块同时使用补值服务 | — | 互不干扰，各自独立管理请求序号和候选状态 |

### 4.7 日志与提示

| 测试编号 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|
| L-01 | 查询失败日志 | 查询接口异常 | 日志仅含稳定错误类型和请求标识，不含姓名或证件号原文 |
| L-02 | 详情失败日志 | 详情接口异常 | 日志仅含稳定错误类型和请求标识，不含姓名或证件号原文 |
| L-03 | 用户可见错误提示 | 网络失败 | 提示面向录入人员，说明未执行补值且原数据保留；不含堆栈、数据库错误文本、上游服务地址 |

---

## 5. 未决前提

| 编号 | 未决事项 | 影响范围 | 当前处理 |
|---|---|---|---|
| U-01 | 候选记录地区信息缺失（null 或空）时，无法核验是否符合当前用户所属地区条件，该候选应如何处理 | 候选查询的过滤逻辑（是否返回该候选）、候选摘要的展示逻辑 | **暂不确定**。不得默认视为匹配成功，不得绕过地区匹配条件。实施前需确认处理策略 |

---

## 6. 材料核对与来源说明

### 6.1 直接支撑本方案的资料

| 资料 | 支撑内容 |
|---|---|
| `materials/software/current-brief.md` | 匹配键（姓名+证件号）、地区条件、字段白名单、排序规则、空值判断规则、确认框要求、时序保护、编号一致性校验、错误提示要求、模块复用边界 |

### 6.2 仅作背景、不进入事实清单的资料

| 资料 | 排除原因 |
|---|---|
| `materials/software/tests/layout-example.txt` | 明确标注为排版和来源识别的测试例子，其中的 CSV 格式、Python 工具、收入数字、准确率数字均非本题事实，不代表已实现或取得成果 |

### 6.3 冲突与旧口径

本次提供的资料中未发现与当前口径冲突的旧写法。`layout-example.txt` 中的内容已由资料本身明确排除，不构成冲突。

### 6.4 无法核验的部分

- 候选记录地区信息缺失时的处理策略：用户已明确回答"暂不确定"，保留为未决前提 U-01。
- 现有系统中补值服务的具体代码实现细节：资料未提供，本方案以伪代码描述目标行为，不声称反映现有代码。