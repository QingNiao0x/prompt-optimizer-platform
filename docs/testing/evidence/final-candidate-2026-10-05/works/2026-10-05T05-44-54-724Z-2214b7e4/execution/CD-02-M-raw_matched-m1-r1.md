<think>The user wants me to write a technical handover document for a synthetic prompt engineering platform. Let me carefully review all the constraints and materials:

**Task requirements:**
- Audience: New maintainers with Java/Vue basics but no project experience
- Only organize current state and evidence, no architecture rewrites
- Don't run commands or modify source code
- State: front-end/back-end separated modular monolith, server uses unified model gateway to call published models, users can't input model keys
- Workbench has 3 areas: original prompts, context, optimization results
- Must distinguish: implemented, planned, undocumented

**Known materials:**
1. Current API inventory has 4 groups: context preparation, planning questions, final enhancement, optimization history
2. history module preserves tenant and workspace scope, uses deleted_at for soft delete
3. Frontend: Vue 3 + TypeScript, Backend: Java 21 + Spring Boot 3, DB: PostgreSQL
4. Roadmap marks team billing, browser extension, more 3rd party logins as TODOs

**Constraints:**
- All feature claims must cite material paths and evidence, can't infer parent components or callers
- Historical migration describes old structure, not auto current structure; current API inventory takes precedence but conflicts must be listed
- No keys, full auth IDs, or user content; no test accounts as formal admins

**To be clarified:**
- Whether handover needs local debugging/troubleshooting section - User answered: yes readonly troubleshooting steps, no install deps, no config changes, no production startup commands
- Two materials conflict on history retention (permanent vs 180 days), neither approved - User answered: list both parallel, don't adopt either as current state

**Test example data in materials/layout-example.txt:** CSV format, Python analysis, 100M revenue, 99% accuracy - these are test data, not facts. Must be filtered out.

**Legacy material:** Old handover says permanent history retention, new draft says 180 days but not approved.

**Deliverables:**
- System overview and module responsibility table
- Actual interfaces and material index
- Current state, TODO, unknown, and conflict lists
- Permission boundaries and handover checklist
- Citations should let receivers find evidence
- Test examples, legacy drafts, formal docs must be separated
- Don't use generic "input unknown/output unknown/acceptance unknown" placeholders

Let me structure this properly:

1. **System Overview**
   - Architecture: front-end/back-end separated modular monolith
   - Tech stack: Vue 3 + TS, Java 21 + Spring Boot 3, PostgreSQL
   - Model invocation: unified gateway calls published models
   - Users cannot input model keys
   - Workbench 3 regions

2. **Module Responsibility Table**
   - Based on current-brief.md and what's documented

3. **Actual Interfaces and Material Index**
   - 4 API groups from current-brief.md
   - Material paths

4. **Current State, TODO, Unknown, Conflict Lists**

5. **Permission Boundaries**

6. **Handover Checklist**

Let me draft this carefully. I need to:
- Avoid inventing things
- Cite materials properly
- Filter out the test example data (CSV, Python, 99%, 100M)
- Note that legacy note says permanent but new draft says 180 days (conflict)
- Include readonly troubleshooting section as user requested
- List TODOs clearly

Let me also note: the task says "现有与规划能力分离" - separate current and planned. The handover doc must clearly distinguish.

Key items from current-brief.md to extract as evidence:
- API controller only describes protocol entry, service layer does business orchestration
- Optimization history list contains: record number, summary, model version, creation time, status
- No billing deduction implementation
- Platform roles and workspace roles are two boundaries
- Models published by admin, regular users select published entries
- Display version and actual call identifier recorded separately
- Don't suggest browser storing API keys
- Context prep and final enhancement use short session numbers, sessions can expire
- Old migration created user-defined vendor table, later retired - put in evolution record, not current admin features
- 99% success rate in tests is fixture only, not production metric
- Error responses only have stable event codes and request identifiers, no complete log platform config
- Frontend routes: home, workbench, history, admin
- Admin UI visibility is not replacement for backend authorization
- Design previews are visual sketches, not deployed screenshots
- Only single-machine local operation shown, no multi-region deployment/disaster recovery

OK let me now write the document. I'll use Chinese since the task is in Chinese.

The structure should be:
1. 文档说明与适用范围 (purpose and scope)
2. 系统概览 (system overview)
3. 模块职责表 (module responsibility table)
4. 实际接口与资料索引 (actual interfaces and material index)
5. 现状清单 (current state list)
6. 待办清单 (TODO list)
7. 未知清单 (unknown list)
8. 冲突清单 (conflict list)
9. 权限边界 (permission boundaries)
10. 只读排查步骤 (read-only troubleshooting steps)
11. 接手检查表 (handover checklist)

Let me write this carefully with citations.

For materials, the paths are:
- materials/project_docs/current-brief.md
- materials/project_docs/tests/layout-example.txt (test example only, not facts)
- materials/project_docs/legacy-note.md

I'll mark evidence with the source path.

Let me also think about the troubleshooting section - user wants:
- Read-only steps
- No install dependencies
- No modify config
- No production startup commands

So this should be things like:
- How to find request IDs from error responses
- How to correlate stable event codes
- How to read error history section (since sessions can expire)
- Note that there's no central log platform per the materials
- Single-machine local operation only

Let me finalize the structure and write.

For module responsibilities, the current-brief.md mentions:
- api/controller (protocol entry)
- service layer (business orchestration)
- history module (with tenant and workspace scope, soft delete)
- identity module (platform vs workspace roles)
- frontend routes: home, workbench, history, admin

The 4 API groups:
1. 上下文准备 (context preparation)
2. 计划提问 (planning questions)
3. 最终增强 (final enhancement)
4. 优化历史 (optimization history)

Workbench 3 regions:
1. 原始提示词 (original prompt)
2. 上下文 (context)
3. 优化结果 (optimization result)

OK I think I'm ready to write. Let me be careful about:
- Don't fabricate facts
- Cite every claim
- Filter test example data
- Note conflicts
- Distinguish implemented/planned/undocumented
- Use Chinese throughout

Let me also think about what is "未确认" (to be confirmed) vs what's "unknown" - the task distinguishes:
- 待办 = planned/TODO (in roadmap but not done)
- 未知 = undocumented
- 冲突 = conflicts in materials

For history retention:
- Old handover says permanent
- New draft says 180 days, not approved
- Both should be listed in conflict section

For local debugging/troubleshooting:
- User confirmed: include read-only steps
- Not include: install deps, modify config, start production services

For test data (CSV, Python, 99%, 100M):
- Filter out, note in a section

Let me write the document now.

One more consideration - I should think about whether to use a table format. The module responsibility table should be a table. The TODO list could be a table. Conflicts should be a table or structured list.

Final document outline:

# 提示词平台技术交接文档

## 0. 文档说明
- Purpose, what this is, what this isn't
- Note about test example file

## 1. 系统概览
- Architecture, tech stack, model invocation model
- Workbench description

## 2. 模块职责表
- Table of modules and responsibilities

## 3. 实际接口与资料索引
- 4 API groups
- Material index

## 4. 现状/待办/未知/冲突清单
- 4.1 现状（已实现）
- 4.2 待办（仅写在路线图）
- 4.3 资料未覆盖
- 4.4 资料冲突

## 5. 权限边界
- Platform roles
- Workspace roles
- Model publishing
- API key handling

## 6. 只读排查步骤
- Read-only troubleshooting

## 7. 接手检查表
- Checklist for new maintainers

## 资料引用说明
- Material paths
- Note about test data, legacy drafts

I'll write this out now.</think>

# 提示词工程平台 — 新维护人员技术交接文档

> 来源标注约定：本文涉及的所有事实均带方括号引用，引用路径指向 `materials/project_docs/` 下的可核查资料；测试示例文件、旧交接草稿与正式说明分开标注。文档不含 API Key、完整认证标识、用户正文或测试账号。

---

## 0. 文档说明与适用范围

- 本文档面向具备 Java 与 Vue 基础但未参与本项目的新维护人员。
- 仅整理"现状 + 证据"，不提出架构重写、不运行命令、不修改源码。
- 文档结论的优先级：**正式说明（current-brief.md）> 旧交接（legacy-note.md，需登记冲突）> 测试示例（layout-example.txt，不作为事实）**。
- `materials/project_docs/tests/layout-example.txt` 仅用于排版识别，其中"CSV 输出 / Python 分析 / 100 万元收入 / 99% 准确率"等条目不是本题事实、不代表已实现能力或线上指标，**本文不予采信**。
- 旧交接材料与新需求草稿中关于"历史保留期限"的差异按本文 §4.4 处理，**不在现状部分擅自采用任一项**。

---

## 1. 系统概览

| 维度 | 当前状态 | 证据 |
|---|---|---|
| 架构形态 | 前后端分离的模块化单体 | [current-brief.md §合成现行资料] |
| 服务端 | Java 21 + Spring Boot 3 | [current-brief.md §合成现行资料] |
| 前端 | Vue 3 + TypeScript | [current-brief.md §合成现行资料] |
| 数据库 | PostgreSQL | [current-brief.md §合成现行资料] |
| 模型调用 | 通过统一模型网关调用**已发布模型**；用户不能输入模型密钥 | [current-brief.md §合成现行资料] + 任务说明 |
| 工作台 | 三个区域：原始提示词 / 上下文 / 优化结果 | 任务说明 + [current-brief.md §最终增强使用短时会话编号] |
| 模型展示 | 显示版本与实际调用标识分开记录 | [current-brief.md §模型列表由后台发布] |
| 部署形态 | 资料仅覆盖单机本地运行；未提供多区域部署或灾备验证 | [current-brief.md §资料只展示单机本地运行] |
| 集中日志 | 资料未给出完整日志平台配置，仅提供稳定事件代码与请求标识 | [current-brief.md §资料给出的错误响应] |

---

## 2. 模块职责表

> 说明：以下职责以"资料中能直接核对的工程事实"为边界。父组件、调用方与未列出的隐含模块**不作推断**。

| 模块 | 职责 | 证据 / 备注 |
|---|---|---|
| `api/controller` | 协议层入口；仅描述请求契约 | [current-brief.md §目录 api/controller] |
| `service` 层 | 业务编排 | [current-brief.md §目录 api/controller] — Controller 不能代替 Service 的错误处理范围 |
| `history` 模块 | 保留租户与工作区范围；删除采用 `deleted_at` 逻辑删除 | [current-brief.md §合成目录中 history 模块] |
| `identity` 模块 | 区分"平台角色"与"工作区角色"两类边界 | [current-brief.md §身份模块材料] |
| 模型发布（后台） | 由后台发布模型列表；普通用户仅可选择已发布条目 | [current-brief.md §模型列表由后台发布] |
| 上下文准备 / 最终增强 | 使用短时会话编号；会话可过期 | [current-brief.md §上下文准备与最终增强] |
| 前端路由 | 首页、工作台、历史、管理员 | [current-brief.md §前端路由清单] |
| 前端设计预览 | 目录中的设计预览为**视觉草图**，不是已部署界面 | [current-brief.md §目录中的设计预览] |

**不在当前文档职责范围内（旧物，仅作演进记录）**：

- "用户自定义供应商表"：旧 migration 创建后已明确退役，不再视为当前管理员功能。[current-brief.md §旧 migration 片段]

---

## 3. 实际接口与资料索引

### 3.1 实际接口（按 current-brief.md 合成当前接口清单）

> 资料给出**四组接口**，未列出每组内的具体路径、方法或字段；接手人需在源码中按组核对每条子接口。

| 接口组 | 业务含义 | 证据 |
|---|---|---|
| 上下文准备 | 为工作台准备上下文与会话 | [current-brief.md §合成当前接口清单] |
| 计划提问 | 与计划相关的提问链路 | [current-brief.md §合成当前接口清单] |
| 最终增强 | 输出最终增强后的提示词 | [current-brief.md §合成当前接口清单] |
| 优化历史 | 历史记录的读写；列表字段含记录编号、摘要、模型版本、创建时间、状态 | [current-brief.md §合成当前接口清单] + [current-brief.md §优化历史清单] |

### 3.2 资料索引

| 资料路径 | 性质 | 使用规则 |
|---|---|---|
| `materials/project_docs/current-brief.md` | 正式说明（合成现行资料） | 作为现状说明的主要依据；接口冲突时优先 |
| `materials/project_docs/legacy-note.md` | 旧交接说明 / 新需求草稿 | 仅作历史保留期限的冲突对照；**不视为现状** |
| `materials/project_docs/tests/layout-example.txt` | 测试示例文件 | 不是事实、不作为能力声明依据 |

---

## 4. 现状 / 待办 / 未知 / 冲突清单

### 4.1 现状（已实现）

| 能力 | 描述 | 证据 |
|---|---|---|
| 模型调用走统一网关 | 网关调用已发布模型；用户不持有 API Key | [current-brief.md §合成现行资料] |
| 历史租户/工作区范围 | `history` 模块带租户 + 工作区范围 | [current-brief.md §合成目录中 history 模块] |
| 历史软删除 | 删除采用逻辑删除字段 | [current-brief.md §合成目录中 history 模块] |
| 历史列表字段 | 记录编号、摘要、模型版本、创建时间、状态 | [current-brief.md §优化历史清单] |
| 平台/工作区角色分离 | 两类角色是两条独立边界 | [current-brief.md §身份模块材料] |
| 模型发布 | 模型由后台发布，用户只选已发布 | [current-brief.md §模型列表由后台发布] |
| 显示版本 vs 调用标识 | 分开记录 | [current-brief.md §模型列表由后台发布] |
| 短时会话编号 | 上下文准备与最终增强使用，会话可过期 | [current-brief.md §上下文准备与最终增强] |
| 前端路由 | 首页 / 工作台 / 历史 / 管理员 | [current-brief.md §前端路由清单] |
| 错误响应 | 包含稳定事件代码与请求标识 | [current-brief.md §资料给出的错误响应] |

### 4.2 待办（仅写在路线图，未实现）

| 待办项 | 来源 | 严禁写法 |
|---|---|---|
| 团队计费 | [current-brief.md §合成路线图] | 不可写为已具备团队计费或账单能力 |
| 浏览器插件 | [current-brief.md §合成路线图] | 不可写为已发布或可用 |
| 更多第三方登录 | [current-brief.md §合成路线图] | 不可写为已接入或可用 |

补充：[current-brief.md §优化历史清单] 明确"给定片段没有计费扣款实现"，因此历史记录**不可解释为账单或支付凭证**。

### 4.3 资料未覆盖（当前不可证伪）

| 项 | 缺口描述 | 证据 |
|---|---|---|
| 全平台模型错误处理范围 | 单个 Controller 文件不足以证明全部模型错误均被处理；需找到统一错误契约证据 | [current-brief.md §目录 api/controller] |
| 全局缓存清理实现 | 资料未给出 | [current-brief.md §上下文准备与最终增强] |
| 集中日志平台配置 | 资料仅给出稳定事件代码 + 请求标识；未提供平台名称或检索入口 | [current-brief.md §资料给出的错误响应] |
| 多区域部署 / 灾备验证 | 仅单机本地运行资料 | [current-brief.md §资料只展示单机本地运行] |
| 管理员界面按钮实际可见性 | 设计预览仅为草图，**不可作为已部署界面截图** | [current-brief.md §目录中的设计预览] |
| 工作台三区域交互细节 | 资料确认存在三个区域，但未提供字段级契约 | 任务说明 + [current-brief.md §合成现行资料] |
| `history` 之外的模块清单 | 资料未给出完整模块树 | — |

### 4.4 资料冲突（需立项，不擅自采用）

> **冲突点：优化历史的保留期限**

| 资料 | 声明 | 状态 |
|---|---|---|
| `materials/project_docs/legacy-note.md`（旧交接说明） | 历史永久保留 | 旧材料，未确认是否仍然有效 |
| `materials/project_docs/legacy-note.md`（新需求草稿） | 历史保留 180 天 | 尚未批准 |

处理原则：
- 本文档**不把任一项作为现状**写入 §4.1；
- 现状部分仅写"采用 `deleted_at` 逻辑删除"，**不写明具体保留天数**；
- 接手人需按"未确认项"对待，并在上线策略会议明确口径后再补回（见 §8 检查表条目 H-2）。

---

## 5. 权限边界

| 边界 | 描述 | 证据 |
|---|---|---|
| 平台角色 vs 工作区角色 | 两个独立边界，不能把工作区管理员写成全平台模型配置管理员 | [current-brief.md §身份模块材料] |
| 模型发布权 | 仅后台可发布；普通用户只读已发布条目 | [current-brief.md §模型列表由后台发布] |
| API Key 持有 | 用户不持有 API Key；不得建议浏览器存放 API Key | [current-brief.md §模型列表由后台发布] + 任务说明 |
| 路由守卫 vs 服务端权限 | 前端管理员界面可见性**不是**后端授权的替代 | [current-brief.md §前端路由清单] |
| 错误处理边界 | 单一 Controller 不等于"所有模型错误均被处理"，需要统一错误契约证据 | [current-brief.md §目录 api/controller] |
| 历史记录性质 | 不构成账单或支付凭证 | [current-brief.md §优化历史清单] |
| 测试夹具指标 | 测试中"99% 成功率"仅为断言夹具，**不是**线上测量值或上线质量承诺 | [current-brief.md §测试文件中出现成功率] |

---

## 6. 只读排查步骤（不安装依赖、不修改配置、不启动生产服务）

> 范围：仅含**只读**操作；不执行安装、配置变更、生产启动、模型调用命令。

1. **定位请求标识**
   - 在接口错误响应中读取 `request_id`（请求标识）与稳定事件代码。[current-brief.md §资料给出的错误响应]
2. **核对稳定事件代码**
   - 仅以资料中给出的稳定事件代码集合作为分类依据；不外推未列出的事件类型。[current-brief.md §资料给出的错误响应]
3. **历史会话排查**
   - 上下文准备 / 最终增强使用**短时会话编号**，会话可过期。若客户端报错，先核对该会话编号是否仍在有效期内。[current-brief.md §上下文准备与最终增强]
   - 历史查询需带租户与工作区范围，删除走 `deleted_at`；排查"看不到记录"时，先区分软删除 vs 范围过滤。[current-brief.md §合成目录中 history 模块]
4. **历史列表字段对照**
   - 记录编号、摘要、模型版本、创建时间、状态；其中"模型版本"为**显示版本**，实际调用标识另存，不要混为一谈。[current-brief.md §优化历史清单] + [current-brief.md §模型列表由后台发布]
5. **角色边界复检**
   - 出现"看不到某功能"或"能看不能改"时，优先怀疑是**平台角色 vs 工作区角色**边界，而非路由问题。[current-brief.md §身份模块材料] + [current-brief.md §前端路由清单]
6. **不要把测试夹具当线上指标**
   - 测试中出现的成功率数字仅是夹具，不能用于线上质量论证。[current-brief.md §测试文件中出现成功率]
7. **集中日志边界**
   - 资料未提供完整日志平台配置。排查时只通过请求标识 + 稳定事件代码关联，不臆造集中日志服务名称。[current-brief.md §资料给出的错误响应]
8. **覆盖范围声明**
   - 当前可核查的范围仅限单机本地运行；多区域 / 灾备相关现象不在当前排查结论范围内。[current-brief.md §资料只展示单机本地运行]

---

## 7. 接手检查表

| 编号 | 项目 | 验证入口 | 状态（接手人填写） |
|---|---|---|---|
| H-1 | 接口清单的 4 组接口在源码中可定位，且与本表 §3.1 一致 | `materials/project_docs/current-brief.md §合成当前接口清单` 与源码核对 | □ |
| H-2 | 历史保留期限冲突（永久 vs 180 天）有正式决议；决议前不得在生产策略中固化其中一项 | `materials/project_docs/legacy-note.md` + §4.4 | □ |
| H-3 | `history` 模块确为软删除；删除流程不触发物理删除 | [current-brief.md §合成目录中 history 模块] | □ |
| H-4 | 平台角色与工作区角色在权限模型中为两条独立边界 | [current-brief.md §身份模块材料] | □ |
| H-5 | 前端未在前端代码或文档中要求用户输入或缓存 API Key | [current-brief.md §模型列表由后台发布] | □ |
| H-6 | 错误响应同时含稳定事件代码与请求标识，且排查流程仅依赖此二者 | [current-brief.md §资料给出的错误响应] | □ |
| H-7 | 工作台三个区域（原始提示词 / 上下文 / 优化结果）在 UI 中可识别且与本表 §1 一致 | 任务说明 + [current-brief.md §合成现行资料] | □ |
| H-8 | 设计预览不被引用为已部署界面 | [current-brief.md §目录中的设计预览] | □ |
| H-9 | "用户自定义供应商表"未被列入当前可用能力 | [current-brief.md §旧 migration 片段] | □ |
| H-10 | 测试夹具数字（99%、100 万、CSV/Python）未被引用为线上指标或能力声明 | `materials/project_docs/tests/layout-example.txt` + [current-brief.md §测试文件中出现成功率] | □ |
| H-11 | 团队计费 / 浏览器插件 / 第三方登录未被任何文档声明为"已具备" | [current-brief.md §合成路线图] | □ |
| H-12 | 历史记录在对外材料中未被描述为账单或支付凭证 | [current-brief.md §优化历史清单] | □ |
| H-13 | 排查手册只使用只读步骤；不依赖安装新依赖或修改配置 | §6 | □ |
| H-14 | 部署与灾备相关结论未被扩展到当前未覆盖范围之外 | [current-brief.md §资料只展示单机本地运行] | □ |

---

## 附录 A：引用速查

| 引用 | 来源 |
|---|---|
| [current-brief.md §合成现行资料] | `materials/project_docs/current-brief.md` |
| [current-brief.md §合成目录中 history 模块] | 同上 |
| [current-brief.md §合成路线图] | 同上 |
| [current-brief.md §目录 api/controller] | 同上 |
| [current-brief.md §优化历史清单] | 同上 |
| [current-brief.md §身份模块材料] | 同上 |
| [current-brief.md §模型列表由后台发布] | 同上 |
| [current-brief.md §上下文准备与最终增强] | 同上 |
| [current-brief.md §旧 migration 片段] | 同上 |
| [current-brief.md §测试文件中出现成功率] | 同上 |
| [current-brief.md §资料给出的错误响应] | 同上 |
| [current-brief.md §前端路由清单] | 同上 |
| [current-brief.md §目录中的设计预览] | 同上 |
| [current-brief.md §资料只展示单机本地运行] | 同上 |
| [legacy-note.md] | `materials/project_docs/legacy-note.md` |
| [layout-example.txt] | `materials/project_docs/tests/layout-example.txt`（非事实） |

---

## 附录 B：未确认项登记（仅保留影响任务目标的业务决定）

| 编号 | 待确认项 | 来源 / 冲突点 | 影响 |
|---|---|---|---|
| U-1 | 优化历史的保留期限 | [legacy-note.md]：永久保留 vs 新草稿 180 天（未批准） | 历史清理策略、存储成本、合规边界 |
| U-2 | 接口清单的四组接口在源码中的字段级契约 | [current-brief.md §合成当前接口清单] 未给出子接口细节 | 排查与对接范围 |
| U-3 | 集中日志平台的存在与配置 | [current-brief.md §资料给出的错误响应] | 关联排查深度 |
| U-4 | 多区域部署 / 灾备方案 | [current-brief.md §资料只展示单机本地运行] | 可用性承诺边界 |
| U-5 | 工作台三区域的字段级交互契约 | 任务说明 | 对接细节 |

> 上述未确认项不属于"输入不明 / 输出不明 / 验收不明"类的占位描述，每项均有具体缺失资料对应的资料范围。
>
> **本文档不擅自补全本地调试与故障排查操作章节的"动手"部分**——按接手人答复仅含只读排查步骤，详见 §6。