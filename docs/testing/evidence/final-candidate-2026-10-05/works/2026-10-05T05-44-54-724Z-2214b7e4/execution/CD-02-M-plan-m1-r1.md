<think>The user wants me to create a technical handover document for new maintainers based on the provided materials. Let me analyze the requirements carefully:

1. The document must be based only on the provided materials
2. Must include specific sections
3. Must distinguish between confirmed, planned, and unknown items
4. Must not include prohibited content (keys, test data treated as facts, etc.)
5. Must handle the historical retention conflict by listing both side-by-side
6. Must only provide read-only troubleshooting steps

Let me organize the materials:

**From current-brief.md (formal/current documentation):**
- Frontend-backend separated modular monolith
- Server uses unified model gateway to call published models, users cannot input model keys
- Workbench provides three areas: raw prompt, context, optimization result
- Current interface list includes 4 groups: context preparation, planned questions, final enhancement, optimization history
- history module retains tenant and workspace scope, soft delete via deleted_at
- Frontend uses Vue 3 and TypeScript
- Backend uses Java 21 and Spring Boot 3
- Database is PostgreSQL
- Roadmap marks team billing, browser extension, and more third-party logins as TODO
- api/controller directory only describes protocol entry; business orchestration by service layer
- Optimization history list includes: record number, summary, model version, creation time, status
- Platform role and workspace role are two boundaries
- Model list is published by backend, regular users only select published entries
- Display version and actual call identifier are recorded separately
- Context preparation and final enhancement use short-term session IDs, sessions can expire
- Old migration fragment created user-defined vendor table, later retired, no longer used
- Error responses only have stable event codes and request identifiers
- Frontend routes include: home, workbench, history, admin pages
- Design previews in catalog are visual sketches
- Materials only show single-machine local run info, no multi-region deployment or disaster recovery validation

**From tests/layout-example.txt (test data, NOT facts):**
- Output format CSV, analysis tool Python, revenue 100万, accuracy 99% - NOT facts about this project

**From legacy-note.md (legacy/draft documentation):**
- Old handover says history permanently retained
- New requirement draft says history retained 180 days, but not yet approved

Now let me structure the handover document:

1. **系统概览和模块职责表** - System overview and module responsibility table
2. **实际接口与资料索引** - Actual interfaces and material index
3. **现状、待办、未知及冲突清单** - Current, TODO, unknown, and conflict list
4. **权限边界与接手检查表** - Permission boundaries and handover checklist
5. **只读排查步骤** - Read-only troubleshooting steps

Constraints to remember:
- All claims must cite material paths and evidence
- Test examples, legacy drafts, and formal specs must be separated
- Don't include keys, full auth identifiers, or user content
- Don't write TODO features as available
- Don't interpret history records as bills/payment proofs
- Don't write workspace admin as platform-wide model config admin
- Don't suggest browser storing API Keys
- Don't fabricate centralized log service names
- Don't treat design previews as deployed screenshots
- Don't give unfounded HA guarantees
- Don't treat 99% success rate from test files as product commitment
- Don't treat test example content as facts
- History retention conflict: list both, don't pick one
- Read-only troubleshooting doesn't include install/config/start commands

Let me write this document in Chinese since the task is in Chinese.

Let me think about the interface details based on the current-brief.md:
- 上下文准备 (Context Preparation)
- 计划提问 (Planned Questions)
- 最终增强 (Final Enhancement)
- 优化历史 (Optimization History)

These are the four interface groups mentioned. I need to note these come from current-brief.md but the actual paths aren't given to me. I should reference the document paths I have.

Let me also think about what I should NOT include:
- Specific API endpoints (not provided in materials)
- Specific table schemas (only know about deleted_at logical delete)
- Specific user/role names
- Specific model names

Let me draft the document now:

---

# 技术交接文档

## 0. 文档说明与资料来源标注

本文档所有事实声明均附资料路径与证据位置。资料按以下分类标注：

- **正式说明**：materials/project_docs/current-brief.md（项目当前正式交接材料）
- **旧版草稿**：materials/project_docs/legacy-note.md（历史交接说明与未批准草稿）
- **测试示例**：materials/project_docs/tests/layout-example.txt（仅用于排版和来源识别，不是本题事实）

未在上述资料中出现的功能、调用方、父组件不得推断存在。

---

## 1. 系统概览和模块职责表

### 1.1 系统形态

| 维度 | 现状 | 证据 |
|------|------|------|
| 架构形态 | 前后端分离的模块化单体 | current-brief.md "前后端分离的模块化单体" |
| 模型调用 | 服务端通过统一模型网关调用已发布模型，用户不能输入模型密钥 | current-brief.md "服务端通过统一模型网关调用已发布模型，用户不能输入模型密钥" |
| 前端栈 | Vue 3 + TypeScript | current-brief.md "前端使用 Vue 3 和 TypeScript" |
| 后端栈 | Java 21 + Spring Boot 3 | current-brief.md "后端使用 Java 21 和 Spring Boot 3" |
| 数据库 | PostgreSQL | current-brief.md "数据库为 PostgreSQL" |
| 运行覆盖 | 资料仅展示单机本地运行信息 | current-brief.md "资料只展示单机本地运行信息，没有多区域部署和灾备验证" |

### 1.2 模块职责表

| 模块/区域 | 职责描述 | 证据 |
|-----------|----------|------|
| 工作台 | 提供原始提示词、上下文、优化结果三个区域 | current-brief.md "工作台提供原始提示词、上下文和优化结果三个区域" |
| 上下文准备 | 接口组之一，使用短时会话编号，会话可过期 | current-brief.md "合成当前接口清单包含上下文准备"；"上下文准备与最终增强使用短时会话编号，资料说明会话可过期" |
| 计划提问 | 接口组之一 | current-brief.md "计划提问"接口组 |
| 最终增强 | 接口组之一，使用短时会话编号，会话可过期 | current-brief.md "最终增强"接口组；"会话可过期" |
| 优化历史 | 接口组之一；保留租户与工作区范围；删除采用 deleted_at 逻辑删除 | current-brief.md "优化历史"接口组；"history 模块保留租户与工作区范围，删除历史采用 deleted_at 逻辑删除" |
| 身份模块 | 平台角色与工作区角色是两个边界 | current-brief.md "身份模块材料表明平台角色与工作区角色是两个边界" |
| 模型列表 | 由后台发布，普通用户只选择已发布条目；显示版本与实际调用标识分开记录 | current-brief.md "模型列表由后台发布，普通用户只选择已发布条目；显示版本与实际调用标识分开记录" |
| Controller 层 | 仅描述协议入口 | current-brief.md "目录 api/controller 只描述协议入口" |
| Service 层 | 负责业务编排 | current-brief.md "业务编排由 service 层负责" |

### 1.3 前端路由清单

| 路由页面 | 证据 |
|----------|------|
| 首页 | current-brief.md "前端路由清单包含首页、工作台、历史和管理员页面" |
| 工作台 | 同上 |
| 历史 | 同上 |
| 管理员 | 同上 |

**注意**：前端路由可见性不是后端授权的替代（current-brief.md "管理员界面可见性不是后端授权的替代"）；接入方需结合后端权限校验判断。

---

## 2. 实际接口与资料索引

### 2.1 合成当前接口清单

依据 current-brief.md "合成当前接口清单"，包含以下四组接口：

| 接口组 | 用途/要点 | 证据 |
|--------|-----------|------|
| 上下文准备 | 使用短时会话编号，会话可过期 | current-brief.md 接口清单 + 会话编号说明 |
| 计划提问 | （资料未提供内部细节） | current-brief.md 接口清单 |
| 最终增强 | 使用短时会话编号，会话可过期 | current-brief.md 接口清单 + 会话编号说明 |

### 2.3 优化历史清单字段

依据 current-brief.md "优化历史清单包含"：

- 记录编号
- 摘要
- 模型版本
- 创建时间
- 状态

**重要边界**：该清单"没有计费扣款实现，因此不能把历史记录解释为账单或支付凭证"（current-brief.md）。

### 2.4 演进/退役记录

- 旧 migration 片段创建了用户自定义供应商表，后续退役记录明确不再使用（current-brief.md）。该表不应出现在当前管理员功能列表。

### 2.5 资料路径索引

| 内容类别 | 路径 |
|----------|------|
| 当前正式说明 | materials/project_docs/current-brief.md |
| 旧版草稿（历史保留期限冲突来源之一） | materials/project_docs/legacy-note.md |
| 测试示例（仅排版/识别用） | materials/project_docs/tests/layout-example.txt |

---

## 3. 现状、待办、未知及冲突清单

### 3.1 已实现（现状）

| 项目 | 证据 |
|------|------|
| 前后端分离模块化单体 | current-brief.md |
| 服务端通过统一模型网关调用已发布模型；用户不能输入模型密钥 | current-brief.md |
| 工作台三区域：原始提示词、上下文、优化结果 | current-brief.md |
| 上下文准备 / 计划提问 / 最终增强 / 优化历史 四组接口 | current-brief.md |
| history 模块保留租户与工作区范围；删除采用 deleted_at 逻辑删除 | current-brief.md |
| Vue 3 + TypeScript 前端 | current-brief.md |
| Java 21 + Spring Boot 3 后端 | current-brief.md |
| PostgreSQL 数据库 | current-brief.md |
| 优化历史清单字段：记录编号、摘要、模型版本、创建时间、状态 | current-brief.md |
| 平台角色与工作区角色为两个边界 | current-brief.md |
| 模型列表由后台发布；普通用户只选择已发布条目；显示版本与实际调用标识分开记录 | current-brief.md |
| 上下文准备、最终增强使用短时会话编号；会话可过期 | current-brief.md |
| Controller 层仅描述协议入口；业务编排由 service 层负责 | current-brief.md |
| 前端路由：首页、工作台、历史、管理员 | current-brief.md |
| 错误响应：稳定事件代码 + 请求标识 | current-brief.md |

### 3.2 仅写在计划里（待办，未实现）

| 项目 | 证据 |
|------|------|
| 团队计费 | current-brief.md "合成路线图中团队计费、浏览器插件和更多第三方登录均标为待办，不能写成可用功能" |
| 浏览器插件 | 同上 |
| 更多第三方登录 | 同上 |

### 3.3 资料未覆盖（未知）

| 项目 | 说明 |
|------|------|
| 多区域部署 | current-brief.md "没有多区域部署" |
| 灾备验证 | current-brief.md "没有灾备验证"——不得据此给出高可用保证 |
| 集中日志平台名称 | current-brief.md "未提供完整日志平台配置"——不得编造集中日志服务名称 |
| 计划提问接口内部细节 | 资料未提供 |
| Controller 错误处理的全部边界 | current-brief.md 提示需要找到统一错误契约证据，单个 Controller 不能宣称所有模型错误都被处理 |
| 用户自定义供应商表当前归属 | 已退役，current-brief.md "不应放在当前管理员功能列表"；当前管理员对模型配置的实际可见范围以正式资料为准 |

### 3.4 冲突清单：历史保留期限

依据 user confirmation + 平台约束："历史保留期限未批准且材料冲突，永久保留与180天并列核对，不擅自采用任何一项作为现状"。

| 来源 | 声明 | 状态 |
|------|------|------|
| materials/project_docs/legacy-note.md（旧交接说明） | 历史永久保留 | 历史材料 |
| materials/project_docs/legacy-note.md（新需求草稿） | 历史保留 180 天 | 尚未批准 |

**对接要求**：两份资料存在差异（user 明确确认）。交接时不擅自采用任一项作为现状；后续以审批结果为准。在做出业务决定前，建议保留为待确认项。

### 3.5 关于测试示例与旧版草稿的区分

- materials/project_docs/tests/layout-example.txt：内容为输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99%。**这些不是本题事实**（current-brief.md + 测试文件本身说明），不得用作产品功能声明或线上测量依据。
- materials/project_docs/legacy-note.md：为旧交接说明与未批准草稿，与正式说明 current-brief.md 存在差异时，以正式说明为准；冲突项（如历史保留期限）按本节 3.4 并列处理。

---

## 4. 权限边界与接手检查表

### 4.1 权限边界

| 边界 | 说明 | 证据 |
|------|------|------|
| 平台角色 vs 工作区角色 | 平台角色与工作区角色是两个边界 | current-brief.md "身份模块材料表明平台角色与工作区角色是两个边界" |
| workspace 管理员 ≠ 全平台模型配置管理员 | 不得把 workspace 管理员写成全平台模型配置管理员 | current-brief.md 边界提示 |
| 模型发布权 | 模型列表由后台发布，普通用户只选择已发布条目 | current-brief.md |
| 模型密钥 | 用户不能输入模型密钥 | current-brief.md；不得建议浏览器存放 API Key |
| 路由守卫 vs 服务端权限 | 管理员界面可见性不是后端授权的替代 | current-brief.md "管理员界面可见性不是后端授权的替代" |

### 4.2 接手检查表

| 检查项 | 资料依据 | 检查方式建议 |
|--------|----------|--------------|
| 确认四组接口（上下文准备、计划提问、最终增强、优化历史）的当前清单 | current-brief.md 接口清单 | 读取合成目录中的接口清单文件（路径以仓库实际为准） |
| 确认 history 模块是否仍以 deleted_at 做逻辑删除 | current-brief.md "删除历史采用 deleted_at 逻辑删除" | 核查 history 模块当前 schema/migration |
| 确认模型列表是否仍由后台发布 | current-brief.md 模型发布说明 | 核查发布接口与前端选择范围 |
| 确认会话编号是否仍为短时且可过期 | current-brief.md 会话说明 | 核查上下文准备/最终增强的实现与过期清理（资料未提供全局缓存清理实现，不擅自补全） |
| 确认旧用户自定义供应商表已不在管理员功能范围 | current-brief.md 退役说明 | 核查当前管理员可配置项 |
| 确认错误响应仅返回稳定事件代码和请求标识 | current-brief.md 错误响应说明 | 核查统一错误契约（用于只读排查关联） |
| 历史保留期限 | legacy-note.md 冲突说明 | **并列核对**永久保留与 180 天，等待审批结果（见 3.4） |
| 多区域/灾备覆盖 | current-brief.md 边界说明 | 不得在没有资料证据的前提下承诺高可用 |

---

## 5. 只读排查步骤

### 5.1 关联稳定事件代码与请求标识（只读）

依据 current-brief.md "错误响应只有稳定事件代码和请求标识"。

排查流程（仅查询/关联类操作，不含安装/修改/启动命令）：

1. 从用户反馈或前端界面取得错误响应中的**稳定事件代码**与**请求标识**。
2. 在错误响应出现的位置（前端展示 / 接口返回 / 现有日志载体）使用事件代码与请求标识做关键字检索，定位同一次请求的关联记录。
3. 若仓库内存在错误码定义文件，按事件代码检索其含义定义。
4. 比对该请求在各层（Controller 入口、Service 编排、模型网关调用）是否留下与请求标识一致的痕迹。
5. 由于资料"未提供完整日志平台配置"（current-brief.md），不得编造集中日志服务名称；只能基于现有日志/响应载体进行关联。

### 5.2 控制器层错误处理边界的核查入口

依据 current-brief.md "不能根据一个 Controller 文件就宣称所有模型错误都被处理，需要找到统一错误契约证据"。

核查入口（只读）：
- 查找项目中的统一错误契约定义（错误码枚举/异常映射/全局异常处理器等位置）。
- 比对 Controller 中抛出的异常是否走该契约。
- 不基于单一 Controller 文件得出"所有模型错误都已处理"的结论。

### 5.3 会话编号过期的只读核查

依据 current-brief.md "上下文准备与最终增强使用短时会话编号，资料说明会话可过期"。

- 在排查"会话失效/重新准备"现象时，先确认请求中的会话编号是否仍在有效期。
- 资料未提供全局缓存清理实现（current-brief.md），不得擅自补充清理步骤。

### 5.4 历史删除方式的只读核查

依据 current-brief.md "删除历史采用 deleted_at 逻辑删除"。

- 排查"历史被删除"现象时，优先以 deleted_at 是否被设置为准。
- 不要在未核实资料前将历史条目描述为账单或支付凭证（current-brief.md "没有计费扣款实现"）。

### 5.5 模型调用标识的只读核查

依据 current-brief.md "显示版本与实际调用标识分开记录"。

- 排查模型版本相关疑问时，分别核对"显示版本"与"实际调用标识"两条记录；不得混用。

### 5.6 排查约束

- 本节不包含安装依赖、修改配置、启动生产服务的命令。
- 不输出密钥、完整认证标识或用户正文。
- 不引用测试示例中的 CSV/Python/100万/99% 等内容作为线上事实。

---

## 附录 A：资料分类速查

| 类别 | 路径 | 用途 |
|------|------|------|
| 正式说明 | materials/project_docs/current-brief.md | 现状与边界的主要依据 |
| 旧版草稿 | materials/project_docs/legacy-note.md | 历史交接与未批准草稿；冲突项并列核对 |
| 测试示例 | materials/project_docs/tests/layout-example.txt | 仅用于排版和来源识别，非本题事实 |

## 附录 B：草图/视觉材料使用边界

依据 current-brief.md "目录中的设计预览属于视觉草图，不能当作已部署的界面截图"——引用时须明确草图用途，避免接手人员寻找不存在的按钮。

## 附录 C：未证实能力的核查入口

依据 current-brief.md "交付文档应包含每个未证实能力的核查入口，不将整篇文档变成待确认问卷"。

下列能力的核查入口（不展开为问卷）：

| 未证实能力 | 核查入口 |
|------------|----------|
| 计划提问接口内部细节 | 仓库内合成目录接口清单 |
| 多区域部署/灾备 | 仓库部署/运维文档；当前未覆盖 |
| 集中日志平台名称 | 仓库日志相关配置；当前未提供 |
| 历史保留期限 | 等待审批结果（见 3.4） |
| 全局会话过期清理实现 | 仓库内会话/缓存相关实现 |

---

OK let me review this document to make sure it meets all requirements:

1. ✅ All claims cited with material paths
2. ✅ Test examples, legacy drafts, formal specs separated
3. ✅ Implemented/planned/unknown/conflict clearly distinguished
4. ✅ History retention conflict listed in parallel
5. ✅ No keys, full auth identifiers, user content
6. ✅ No TODO features written as available
7. ✅ Test example content not treated as facts
8. ✅ Read-only troubleshooting doesn't include install/config/start commands
9. ✅ Don't propose architecture rewrite
10. ✅ Don't run commands, change source
11. ✅ Don't fabricate centralized log service names
12. ✅ Don't give unfounded HA guarantees
13. ✅ Module responsibility table present
14. ✅ Actual interfaces and material index present
15. ✅ Status/TODO/unknown/conflict list present
16. ✅ Permission boundaries and handover checklist present
17. ✅ Read-only troubleshooting steps present

I think this is good. Let me finalize the document.

One thing to note - the document should NOT contain the "测试示例" content as actual facts (CSV, Python, 100万, 99%). I've avoided this and explicitly noted it's not a fact.

Let me also make sure I'm not inferring things not in the materials. For example, I shouldn't list specific routes with parameters or specific table names.

I think the document is ready. Let me clean it up and format it properly.

Actually, I notice I have a numbering issue. I have "2.1 合成当前接口清单" but then "2.3 优化历史清单字段" - missing 2.2. Let me fix that.

Also, I should double-check the requirements:
- 模块职责表 - need a clear module responsibility table ✓
- 实际接口与资料索引 - interface list and material index ✓
- 现状、待办、未知及冲突清单 - status/TODO/unknown/conflict ✓
- 权限边界与接手检查表 - permission boundaries and handover checklist ✓
- 只读排查步骤 - read-only troubleshooting steps ✓

Wait, I need to re-read the requirements. The output says:
1. 系统概览和模块职责表
2. 实际接口与资料索引
3. 现状、待办、未知及冲突清单
4. 权限边界与接手检查表
5. 只读排查步骤

The task also mentions:
6. 历史保留期限冲突并列核对

This should be part of section 3 (conflict list). I have it there.

Let mefinalize the document.</think>

# 技术交接文档（合成项目·新维护人员）

## 0. 文档使用说明与资料分类标注

本文档所有功能声明均附资料路径与对应证据。资料按以下三类分开标注：

- **正式说明**：materials/project_docs/current-brief.md（项目当前正式交接材料，下文简称"正式说明"）
- **旧版草稿**：materials/project_docs/legacy-note.md（历史交接说明与未批准草稿，下文简称"旧版草稿"）
- **测试示例**：materials/project_docs/tests/layout-example.txt（仅用于排版和来源识别，下文简称"测试示例"）

未在上述资料中出现的功能、调用方、父组件不得推断存在。能由给定资料核查的常规细节在本文档内自行整理；只有真正影响任务目标且目前缺失的业务决定保留为待确认项。

---

## 1. 系统概览和模块职责表

### 1.1 系统形态

| 维度 | 现状描述 | 证据 |
|------|----------|------|
| 架构形态 | 前后端分离的模块化单体 | 正式说明"前后端分离的模块化单体" |
| 模型调用 | 服务端通过统一模型网关调用已发布模型；用户不能输入模型密钥 | 正式说明"服务端通过统一模型网关调用已发布模型，用户不能输入模型密钥" |
| 前端栈 | Vue 3 + TypeScript | 正式说明"前端使用 Vue 3 和 TypeScript" |
| 后端栈 | Java 21 + Spring Boot 3 | 正式说明"后端使用 Java 21 和 Spring Boot 3" |
| 数据库 | PostgreSQL | 正式说明"数据库为 PostgreSQL" |
| 运行覆盖 | 仅展示单机本地运行信息；无多区域部署和灾备验证 | 正式说明"资料只展示单机本地运行信息，没有多区域部署和灾备验证" |

### 1.2 模块职责表

| 模块 / 区域 | 职责描述 | 证据 |
|-------------|----------|------|
| 工作台 | 提供原始提示词、上下文、优化结果三个区域 | 正式说明"工作台提供原始提示词、上下文和优化结果三个区域" |
| 上下文准备（接口组） | 合成当前接口清单四组之一；使用短时会话编号，会话可过期 | 正式说明"合成当前接口清单" + "上下文准备与最终增强使用短时会话编号，资料说明会话可过期" |
| 计划提问（接口组） | 合成当前接口清单四组之一 | 正式说明"合成当前接口清单" |
| 最终增强（接口组） | 合成当前接口清单四组之一；使用短时会话编号，会话可过期 | 正式说明"合成当前接口清单" + "会话可过期" |
| 优化历史（接口组） | 合成当前接口清单四组之一；保留租户与工作区范围；删除采用 deleted_at 逻辑删除 | 正式说明"history 模块保留租户与工作区范围，删除历史采用 deleted_at 逻辑删除" |
| 身份模块 | 平台角色与工作区角色是两个边界 | 正式说明"身份模块材料表明平台角色与工作区角色是两个边界" |
| 模型列表 | 由后台发布；普通用户只选择已发布条目；显示版本与实际调用标识分开记录 | 正式说明"模型列表由后台发布，普通用户只选择已发布条目；显示版本与实际调用标识分开记录" |
| api/controller 目录 | 只描述协议入口 | 正式说明"目录 api/controller 只描述协议入口" |
| service 层 | 负责业务编排 | 正式说明"业务编排由 service 层负责" |

### 1.3 前端路由清单

| 路由页面 | 证据 |
|----------|------|
| 首页 / 工作台 / 历史 / 管理员 | 正式说明"前端路由清单包含首页、工作台、历史和管理员页面" |

**边界提示**：前端路由可见性不是后端授权的替代（正式说明）。接手方需结合后端权限校验判断实际可见范围。

---

## 2. 实际接口与资料索引

### 2.1 合成当前接口清单（四组）

依据正式说明"合成当前接口清单包含上下文准备、计划提问、最终增强和优化历史四组接口"：

| 接口组 | 用途 / 关键点 | 证据 |
|--------|---------------|------|
| 上下文准备 | 使用短时会话编号；会话可过期 | 正式说明 |
| 计划提问 | 资料未提供内部细节（视为只列入口，不展开） | 正式说明 |
| 最终增强 | 使用短时会话编号；会话可过期 | 正式说明 |
| 优化历史 | 保留租户与工作区范围；删除采用 deleted_at 逻辑删除 | 正式说明 |

### 2.2 优化历史清单字段

依据正式说明"优化历史清单包含"：

- 记录编号
- 摘要
- 模型版本
- 创建时间
- 状态

**重要边界**（正式说明）："没有计费扣款实现，因此不能把历史记录解释为账单或支付凭证"。

### 2.3 错误响应契约

依据正式说明"错误响应只有稳定事件代码和请求标识"，排查时可据此关联（见第 5 节）。资料未提供完整日志平台配置，不得据此编造集中日志服务名称。

### 2.4 演进 / 退役记录

- 旧 migration 片段创建了用户自定义供应商表，后续退役记录明确不再使用（正式说明）。该表不应出现在当前管理员功能列表。

### 2.5 资料路径索引

| 内容类别 | 路径 |
|----------|------|
| 当前正式说明 | materials/project_docs/current-brief.md |
| 旧版草稿（含历史保留期限冲突来源） | materials/project_docs/legacy-note.md |
| 测试示例（仅排版/识别用） | materials/project_docs/tests/layout-example.txt |

### 2.6 设计预览使用边界

正式说明"目录中的设计预览属于视觉草图，不能当作已部署的界面截图"。引用时须注明为草图，避免接手人员按截图寻找不存在的按钮或交互。

---

## 3. 现状、待办、未知及冲突清单

### 3.1 已实现（现状）

| 项目 | 证据 |
|------|------|
| 前后端分离模块化单体 | 正式说明 |
| 服务端通过统一模型网关调用已发布模型；用户不能输入模型密钥 | 正式说明 |
| 工作台三区域：原始提示词、上下文、优化结果 | 正式说明 |
| 上下文准备 / 计划提问 / 最终增强 / 优化历史 四组接口 | 正式说明 |
| history 模块保留租户与工作区范围；删除采用 deleted_at 逻辑删除 | 正式说明 |
| 前端 Vue 3 + TypeScript；后端 Java 21 + Spring Boot 3；数据库 PostgreSQL | 正式说明 |
| 优化历史清单字段：记录编号、摘要、模型版本、创建时间、状态 | 正式说明 |
| 平台角色与工作区角色为两个边界 | 正式说明 |
| 模型列表由后台发布；普通用户只选择已发布条目；显示版本与实际调用标识分开记录 | 正式说明 |
| 上下文准备、最终增强使用短时会话编号；会话可过期 | 正式说明 |
| api/controller 只描述协议入口；service 层负责业务编排 | 正式说明 |
| 前端路由：首页、工作台、历史、管理员 | 正式说明 |
| 错误响应：稳定事件代码 + 请求标识 | 正式说明 |

### 3.2 仅写在计划里（待办，未实现）

| 项目 | 证据 |
|------|------|
| 团队计费 | 正式说明"合成路线图中团队计费、浏览器插件和更多第三方登录均标为待办，不能写成可用功能" |
| 浏览器插件 | 同上 |
| 更多第三方登录 | 同上 |

### 3.3 资料未覆盖（未知）

| 项目 | 说明 | 证据 |
|------|------|------|
| 多区域部署 | 资料未覆盖 | 正式说明"没有多区域部署" |
| 灾备验证 | 资料未覆盖；不得在没有证据的前提下给出高可用保证 | 正式说明"没有灾备验证" |
| 集中日志平台名称 | 资料未提供完整日志平台配置；不得编造服务名称 | 正式说明"未提供完整日志平台配置" |
| 计划提问接口内部细节 | 资料仅列入清单 | 正式说明 |
| Controller 错误处理总边界 | 单个 Controller 文件不能代表所有模型错误都已处理；需找到统一错误契约证据 | 正式说明"不能根据一个 Controller 文件就宣称所有模型错误都被处理" |
| 用户自定义供应商表当前归属 | 已退役，不应出现在当前管理员功能列表 | 正式说明"旧 migration 片段创建了用户自定义供应商表，后续退役记录明确不再使用" |
| 全局缓存清理实现 | 资料未提供 | 正式说明"不给出材料未提供的全局缓存清理实现" |

### 3.4 冲突清单：历史保留期限

依据用户已确认信息与平台约束："历史保留期限未批准且材料冲突，永久保留与 180 天并列核对，不擅自采用任何一项作为现状"。

| 来源 | 声明 | 状态 |
|------|------|------|
| 旧版草稿（materials/project_docs/legacy-note.md，旧交接说明） | 历史永久保留 | 历史材料 |
| 旧版草稿（materials/project_docs/legacy-note.md，新需求草稿） | 历史保留 180 天 | 尚未批准 |

**对接要求**：当前接口清单优先用于现状说明（正式说明），但本项在旧版草稿与正式说明之间未给出最终口径。交接时**并列列出**永久保留与 180 天两项，**不擅自采用任一项**作为现状；正式说明中关于 history 模块的范围与删除方式仍作为现状描述，历史"保留期限"在审批前作为待确认项。

### 3.5 测试示例与旧版草稿的处理边界

- **测试示例**（materials/project_docs/tests/layout-example.txt）：内容包含输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99%。该文件仅用于排版和来源识别，**不是本题事实**，不得用作产品功能声明、线上测量依据或收入/准确率承诺。
- **旧版草稿**（materials/project_docs/legacy-note.md）：与正式说明存在差异时，现状描述以正式说明为准；冲突项（如历史保留期限）按 3.4 并列处理。
- **正式说明**（materials/project_docs/current-brief.md）：作为现状与边界的主要依据。

---

## 4. 权限边界与接手检查表

### 4.1 权限边界

| 边界 | 说明 | 证据 |
|------|------|------|
| 平台角色 vs 工作区角色 | 平台角色与工作区角色是两个边界，二者用途不同 | 正式说明"身份模块材料表明平台角色与工作区角色是两个边界" |
| workspace 管理员 ≠ 全平台模型配置管理员 | 不得将 workspace 管理员扩展为全平台模型配置管理员 | 正式说明"不能把 workspace 管理员写成全平台模型配置管理员" |
| 模型发布权 | 模型列表由后台发布，普通用户只选择已发布条目 | 正式说明 |
| 模型密钥 | 用户不能输入模型密钥；不得建议浏览器存放 API Key | 正式说明 |
| 路由守卫 vs 服务端权限 | 管理员界面可见性不是后端授权的替代 | 正式说明"管理员界面可见性不是后端授权的替代" |

### 4.2 接手检查表

| 检查项 | 资料依据 | 核查方式（只读） |
|--------|----------|------------------|
| 确认四组接口（上下文准备 / 计划提问 / 最终增强 / 优化历史）的当前清单 | 正式说明 接口清单 | 仓库内接口清单目录 |
| 确认 history 模块是否仍以 deleted_at 做逻辑删除 | 正式说明"删除历史采用 deleted_at 逻辑删除" | 核查 history 模块当前 schema / migration |
| 确认模型列表是否仍由后台发布；普通用户仅选择已发布条目 | 正式说明 模型发布说明 | 核查发布接口与前端可选范围 |
| 确认上下文准备 / 最终增强会话编号仍为短时且可过期 | 正式说明 会话说明 | 核查接口实现与过期清理（资料未提供全局缓存清理实现，不擅自补全） |
| 确认旧用户自定义供应商表已不在管理员功能范围 | 正式说明 退役说明 | 核查当前管理员可配置项 |
| 确认错误响应仅返回稳定事件代码和请求标识 | 正式说明 错误响应说明 | 核查统一错误契约定义 |
| 确认 Controller 仅协议入口、业务编排归 service 层 | 正式说明 分层说明 | 抽样核查 controller / service 职责 |
| 历史保留期限 | 旧版草稿 冲突说明 | **并列核对**永久保留与 180 天，等待审批结果（见 3.4） |
| 多区域 / 灾备覆盖 | 正式说明 边界说明 | 不得在没有资料证据的前提下承诺高可用 |

---

## 5. 只读排查步骤

> 本节只整理只读排查思路，不包含安装依赖、修改配置或启动生产服务的命令；不输出密钥、完整认证标识或用户正文；不引用测试示例内容作为线上事实。

### 5.1 通过稳定事件代码与请求标识关联（只读）

依据正式说明"错误响应只有稳定事件代码和请求标识"。

1. 从用户反馈或前端展示取得错误响应中的**稳定事件代码**与**请求标识**。
2. 在错误响应出现的位置（前端展示、接口返回体、现有日志载体）以这两个字段作为关键字进行检索，定位同一次请求的痕迹。
3. 若仓库中存在错误码定义文件（错误枚举 / 异常映射 / 全局异常处理器），按事件代码检索其含义定义。
4. 比对本次请求在各层（Controller 入口、Service 编排、模型网关调用）是否留下与请求标识一致的痕迹。
5. 由于资料"未提供完整日志平台配置"（正式说明），不得编造集中日志服务名称；只能基于现有日志 / 响应载体进行关联。

### 5.2 Controller 错误处理边界的核查入口（只读）

依据正式说明"不能根据一个 Controller 文件就宣称所有模型错误都被处理，需要找到统一错误契约证据"。

- 查找仓库内统一错误契约定义位置（错误码枚举、异常映射、全局异常处理器等）。
- 比对 Controller 抛出的异常是否走该契约。
- 不基于单一 Controller 文件得出"所有模型错误都已处理"的结论。

### 5.3 会话编号过期的只读核查

依据正式说明"上下文准备与最终增强使用短时会话编号，资料说明会话可过期"。

- 排查"会话失效 / 重新准备"现象时，先确认请求中的会话编号是否仍在有效期。
- 资料未提供全局缓存清理实现（正式说明），不擅自补充清理步骤。

### 5.4 历史删除方式的只读核查

依据正式说明"删除历史采用 deleted_at 逻辑删除"。

- 排查"历史被删除"现象时，以 deleted_at 是否被设置为准。
- 不将历史条目描述为账单或支付凭证（正式说明"没有计费扣款实现"）。

### 5.5 模型调用标识的只读核查

依据正式说明"显示版本与实际调用标识分开记录"。

- 排查模型版本相关疑问时，分别核对"显示版本"与"实际调用标识"两条记录；不得混用。

### 5.6 模型密钥与权限排查的边界

- 用户不能输入模型密钥（正式说明）；排查时不应建议浏览器存放 API Key。
- 平台角色与工作区角色是两个边界（正式说明）；排查权限问题时不将 workspace 管理员当作全平台模型配置管理员。

### 5.7 历史保留期限相关排查

依据 3.4，历史保留期限未批准且材料冲突。

- 排查"历史被清理 / 历史