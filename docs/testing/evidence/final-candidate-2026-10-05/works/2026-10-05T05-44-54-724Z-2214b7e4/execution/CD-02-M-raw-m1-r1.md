<think>The user wants me to create a technical handover document based on synthetic prompt word platform materials. Let me analyze what's needed:

1. **Task**: Create a technical handover document for new maintenance personnel about a prompt word management platform
2. **Audience**: Readers with basic Java and Vue experience, but haven't participated in the project
3. **Scope**: Only organize current state and evidence, no architecture rewriting, no running commands, no source code changes
4. **Current state**: Front-end and back-end separated modular monolith, server calls published models through unified model gateway, users cannot input model keys, workspace provides raw prompt, context, and optimization results three areas

**Known materials**:
1. Current API list contains 4 groups: context preparation, plan question, final enhancement, optimization history
2. History module preserves tenant and workspace scope, deletion uses deleted_at soft delete
3. Frontend uses Vue 3 + TypeScript, backend uses Java 21 + Spring Boot 3, database PostgreSQL
4. Team billing, browser extension, more third-party logins are marked as TODO

**Constraints**:
- All function declarations must attach given material paths and corresponding evidence
- Historical migration describes historical structure, doesn't automatically represent current structure
- Don't include keys, complete authentication identifiers, or user content
- Don't write test accounts as formal administrators

**Pending clarifications**:
- Whether handover personnel need local debugging and troubleshooting operations chapter
- Two materials have differences: old handover says history permanently retained; new requirement draft says 180 days but not approved

**Deliverables**:
1. System overview and module responsibility table
2. Actual interfaces and material index
3. Current state, TODO, unknown, and conflict list
4. Permission boundaries and handover checklist

Let me look at the materials carefully:

From `materials/project_docs/current-brief.md`:
- Current API list contains 4 groups: context preparation, plan question, final enhancement, optimization history
- History module preserves tenant and workspace scope, deletion uses deleted_at soft delete
- Frontend Vue 3 + TypeScript, backend Java 21 + Spring Boot 3, PostgreSQL
- Team billing, browser extension, more third-party logins are TODO
- Branch and boundary notes:
  - api/controller only describes protocol entry, business orchestration by service layer
  - Optimization history list contains: record number, summary, model version, creation time, status
  - No billing deduction implementation in given fragments
  - Platform roles and workspace roles are two separate boundaries
  - Model list is published by admin, regular users only select published items
  - Display version and actual call identifier are recorded separately
  - Context preparation and final enhancement use short-term session numbers, sessions can expire
  - Old migration fragment created user-defined supplier table, later retired
  - Test file 99% success rate is just assertion fixture
  - Error response only provides stable event codes and request identifiers
  - Frontend routes include home, workspace, history, admin pages
  - Design previews are visual sketches
  - Only single-machine local operation info shown

From `materials/project_docs/tests/layout-example.txt`:
- Test example only, not facts for this question

From `materials/project_docs/legacy-note.md`:
- Old handover says history permanently retained
- New draft says 180 days but not approved

Now I need to write the handover document. Let me structure it:

1. **System Overview** - What the system is
2. **Module Responsibility Table** - List of modules and their responsibilities
3. **Actual Interfaces and Material Index** - API list with material references
4. **Current State, TODO, Unknown, and Conflict List** - Clear categorization
5. **Permission Boundaries and Handover Checklist** - Security and onboarding

Important things to note:
- Don't include any test accounts as admin
- Distinguish test examples, old drafts, and formal descriptions
- Material citations must allow receivers to find sources
- Don't use generic "input unknown, output unknown, acceptance unknown" - be specific
- Merge duplicate explanations under same conditions
- Important premises can't only be in page hints

Let me draft this now.

I need to be careful about:
- Only using information from given materials
- Clearly distinguishing implemented, planned, and unknown
- Citing material paths
- Marking conflicts as conflicts, not choosing sides
- Keeping the focus tight on the task

Let me structure the document carefully. Since the audience has basic Java/Vue experience but no project experience, I should provide enough context but stay focused.

The constraint about "history retention conflict" - I need to mention both versions and flag this as unapproved/pending.

The pending question about whether to include local debugging/troubleshooting chapter - I should NOT include it by default since it's undecided. I should mention this as a pending decision.

Let me write the document now.</think>

# 合成提示词整理平台 — 技术交接文档（现状与证据版）

> **文档定位**：本稿只整理当前可见的合成资料证据，不进行架构重写、不运行命令、不修改源码。所有"已实现 / 待办 / 未知 / 冲突"均按给定资料原文区分，未在资料中出现的调用方、父组件或下游服务不进行推断。
> **读者假设**：具备 Java 21 / Spring Boot 3 / Vue 3 + TypeScript 基础，但未参与本项目。
> **资料口径**：以 `current-brief.md`（合成现行资料）为主线；`legacy-note.md` 与 `tests/layout-example.txt` 作为历史与排版识别材料，不混入事实层。

---

## 1. 系统概览

合成提示词整理平台是一个**前后端分离的模块化单体**，部署形态仅在资料中以"单机本地运行"呈现，未给出多区域或灾备验证材料。

- **后端**：Java 21 + Spring Boot 3，PostgreSQL 存储。控制器层（`api/controller`）只承担协议入口，业务编排由 service 层负责（见 `current-brief.md` §分支与边界）。
- **前端**：Vue 3 + TypeScript。已识别路由含首页、工作台、历史、管理员页面（同上）。
- **模型调用**：服务端通过**统一模型网关**调用"已发布"模型；用户**不能**输入模型密钥，浏览器端不持有 API Key（同上）。
- **工作台结构**：用户在三个区域中工作——**原始提示词 / 上下文 / 优化结果**（任务说明 + `current-brief.md` §分支与边界）。
- **租户边界**：保留**租户（tenant）与工作区（workspace）**两个范围，且身份模块明确"平台角色 ≠ 工作区角色"（同上）。

---

## 2. 模块职责表

| 模块 | 职责范围（按资料） | 边界与未覆盖项 |
|---|---|---|
| **上下文准备** | 接收会话编号，向后端装配上下文；使用短时会话编号，会话可过期 | 资料未给出过期时长与清理触发器；全局缓存清理实现未在资料中提供 |
| **计划提问** | 在优化过程中生成结构化提问，引导用户澄清需求 | 提问模板、并发限制未在资料中给出 |
| **最终增强** | 输出最终优化提示词，承接计划提问结果 | 仅描述为"短时会话编号"，未列出输出字段契约 |
| **优化历史（history）** | 记录并查询优化历史；保留 tenant + workspace 双范围；删除采用 `deleted_at` 逻辑删除 | 历史保留时长存在冲突（见 §4 冲突清单），账单/支付语义不存在（资料无扣款实现） |
| **身份与权限** | 区分**平台角色**与**工作区角色**两类边界；前端路由守卫不等同后端授权 | 完整角色矩阵未在资料中给出 |
| **模型发布** | 模型列表由后台发布；前端仅展示已发布条目；显示版本与实际调用标识分开记录 | 调用标识如何生成、是否轮换未在资料中说明 |
| **管理员界面** | 提供管理员页面路由可见性 | 仅路由层可见；后端权限校验为独立边界 |

---

## 3. 实际接口与资料索引

合成现行接口清单包含 **4 组接口**，按资料原文归类（来源：`current-brief.md` §现有与规划能力分离）：

| 接口组 | 资料中的位置 | 可引用的进一步证据 | 状态 |
|---|---|---|---|
| 上下文准备（context preparation） | `current-brief.md` §现有与规划能力分离 | §分支与边界（短时会话编号、可过期） | 已列入清单 |
| 计划提问（plan question） | `current-brief.md` §现有与规划能力分离 | — | 已列入清单 |
| 最终增强（final enhancement） | `current-brief.md` §现有与规划能力分离 | §分支与边界（短时会话编号） | 已列入清单 |
| 优化历史（optimization history） | `current-brief.md` §现有与规划能力分离 | §分支与边界（清单字段：记录编号、摘要、模型版本、创建时间、状态；`deleted_at` 逻辑删除） | 已列入清单 |

**索引提示**：以下内容仅作为资料检索路径，不在本稿中复述细节：

- `materials/project_docs/current-brief.md`：合成现行资料（事实主线）。
- `materials/project_docs/legacy-note.md`：旧交接说明与未批准草稿（仅用于 §4 冲突识别）。
- `materials/project_docs/tests/layout-example.txt`：**排版与来源识别测试用例**，与本题事实无关，不作为依据。

---

## 4. 现状 / 待办 / 未知 / 冲突清单

### 4.1 已实现（资料可直接核对）

- 4 组接口的清单存在：`context preparation` / `plan question` / `final enhancement` / `optimization history`。
- `history` 模块保留 tenant + workspace 范围，删除采用 `deleted_at` 逻辑删除。
- 前后端技术栈：Vue 3 + TypeScript / Java 21 + Spring Boot 3 / PostgreSQL。
- 统一模型网关调用已发布模型，**用户不能输入模型密钥**；显示版本与实际调用标识分开记录。
- 工作台三区域：原始提示词、上下文、优化结果。
- 平台角色与工作区角色是两个独立边界；前端路由守卫不替代后端授权。
- `api/controller` 仅协议入口；业务编排由 service 层负责（仅由目录关系可推断，不能扩展为"所有模型错误均被处理"——需在统一错误契约证据出现后再论）。
- 错误响应仅提供**稳定事件代码与请求标识**；可用于跨服务关联（具体日志平台名资料未给出）。

### 4.2 仅写在计划里（按资料标记为待办，不得写成可用功能）

- **团队计费**：合成路线图中明确为待办。
- **浏览器插件**：同上。
- **更多第三方登录**：同上。
- 此外，"统一错误契约的完整实现"、"全局缓存清理实现"、"多区域部署 / 灾备"在资料中均未提供实现证据，不可写入"已具备"。

### 4.3 资料未覆盖（不可推断）

- 上下文准备 / 最终增强的会话过期时长与清理触发机制。
- 模型调用标识的生成与轮换策略。
- 平台角色 / 工作区角色的完整矩阵与操作映射。
- 测试文件中"99% 成功率"——仅为断言夹具（来源：`current-brief.md` §分支与边界），**既非线上测量值，也非产品承诺**，不得用于证明上线质量。
- 旧 `migration` 片段曾创建"用户自定义供应商表"，后续退役记录已明确不再使用——应放在**演进记录**中，不进入当前管理员功能列表。
- `materials/project_docs/tests/layout-example.txt` 中的 CSV / Python / 100 万元 / 99% 等示例字段与本题无关，不得作为工具偏好或事实引用。
- 目录中的"设计预览"为视觉草图，不是已部署界面截图，引用时须注明草图性质。

### 4.4 冲突清单（不擅自裁决）

- **历史保留时长**：
  - 旧交接说明（`legacy-note.md`）：**历史永久保留**。
  - 新需求草稿（`legacy-note.md`）：**历史保留 180 天**，但**尚未批准**。
  - 本稿仅并列展示两条口径，不采纳其中一项。后续处理应以审批结论为准。

---

## 5. 权限边界

按 `current-brief.md` §分支与边界：

1. **平台角色 vs 工作区角色**：二者用途不同。**不得**把"workspace 管理员"等同于"全平台模型配置管理员"。
2. **模型发布**：模型列表由后台发布；普通用户**只能选择已发布条目**，不能自定义接入密钥或供应商。
3. **前端路由可见性 ≠ 后端授权**：管理员页面路由存在不等于操作权限存在；后端授权是独立校验边界。
4. **错误响应**：仅稳定事件代码 + 请求标识可用于排障关联；资料未给出集中日志平台名称，禁止编造。
5. **密钥/凭据边界**：本稿**不写入任何 API Key、完整认证标识、用户正文**；测试账号**不得**作为正式管理员账号描述。

---

## 6. 接手检查表

按"已实现 / 待办 / 未知 / 冲突"四类列出上手核对动作，每项均可在给定资料中追溯：

**A. 现状核对（可独立完成）**

- [ ] 打开 `current-brief.md`，确认 4 组接口名称与本文 §3 一致。
- [ ] 在后端目录确认 `api/controller` 与 service 层的分层结构（不要仅凭一个 Controller 文件推断全量行为）。
- [ ] 确认 `history` 模块中存在 tenant / workspace 范围字段，以及 `deleted_at` 列。
- [ ] 确认前端路由表含首页、工作台、历史、管理员页面；并区分路由守卫与后端授权。
- [ ] 确认模型列表由后台管理，普通用户界面无密钥输入入口。
- [ ] 确认工作台存在"原始提示词 / 上下文 / 优化结果"三个区域。

**B. 待办项登记（不得写入可用功能）**

- [ ] 团队计费、浏览器插件、更多第三方登录：仅在路线图中登记为 TODO。
- [ ] 旧"用户自定义供应商表"：登记为**已退役演进项**，不放入当前管理员功能清单。

**C. 未知项留痕（不擅自补全）**

- [ ] 会话过期时长与清理触发：等待资料补齐后再写入文档。
- [ ] 完整角色矩阵：等待平台角色与工作区角色完整定义。
- [ ] 集中日志平台名：错误关联仅依赖事件代码 + 请求标识。

**D. 冲突项处理（需用户/审批决定后再更新）**

- [ ] **历史保留时长**：旧版"永久保留" vs 新草稿"180 天"——等待审批结论；本文档不预先选择。

**E. 资料索引（保持可追溯）**

- [ ] `current-brief.md`：事实主线，引用时优先。
- [ ] `legacy-note.md`：仅用于冲突识别与历史口径。
- [ ] `tests/layout-example.txt`：排版与来源识别测试用例，**不得**作为事实或工具偏好引用。

---

## 7. 待确认项（影响任务目标的、目前缺失的业务决定）

以下事项在合成资料中未给出，且会影响接手动作的边界，因此保留为**待确认项**而非由本稿自行补全：

1. **是否需要在交接文档中加入"本地调试与故障排查操作"章节**：题目已明确"目前尚未决定，不得默认补全"。本稿**未包含**该章节。
2. **历史保留时长**：见 §4.4 与 §6.D，等待审批。
3. **第三方登录扩展、团队计费、浏览器插件**的优先级与负责人：资料中仅有 TODO 标签，未给出归属。
4. **统一错误契约的完整定义**：仅知道"稳定事件代码 + 请求标识"两层，缺少错误码全集。

> 后续若上述任一项获得审批或补齐资料，应在本稿对应小节直接更新，并以资料路径标注来源；不得在未获新证据时将"未知"改写为"已实现"。