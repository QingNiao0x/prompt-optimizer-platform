<think>Let me analyze the task carefully. I need to create a technical handover document for new maintainers based on the provided materials. Let me review what I have:

**Materials provided:**
1. `materials/project_docs/current-brief.md` - Current official brief
2. `materials/project_docs/tests/layout-example.txt` - Test fixture (not real facts)
3. `materials/project_docs/legacy-note.md` - Legacy note with conflicting retention info

**Key constraints from user clarification:**
- Include read-only troubleshooting steps (no install, no config changes, no prod start commands)
- History retention: note both "permanent" and "180 days" as conflicting, don't pick either

**What I need to deliver:**
1. System overview and module responsibility table
2. Actual interfaces and materials index
3. Status/TODO/Unknown/Conflict list
4. Permission boundaries and handover checklist

**Critical rules:**
- All feature claims must cite material paths and evidence
- Don't infer parent components or callers that aren't in materials
- Distinguish implemented vs planned vs uncovered
- Don't include secrets, full auth identifiers, user content
- Don't write test accounts as formal admins
- Don't write TODOs as available features
- Don't use 99% test fixture as production measurement
- Don't fabricate centralized logging service names
- Don't give unsupported HA guarantees
- Don't treat design previews as deployed screenshots
- Highlight history retention conflict, don't pick one
- Don't assume need for local debug/troubleshooting section (but user confirmed: yes, read-only only)

Let me also think about what's in the materials:

**Frontend routes** (from current-brief):
- 首页 (Home)
- 工作台 (Workspace)
- 历史 (History)
- 管理员 (Admin)

**Backend stack:**
- Java 21 + Spring Boot 3
- PostgreSQL

**Frontend stack:**
- Vue 3 + TypeScript

**API interface groups** (4 groups):
- 上下文准备 (Context preparation)
- 计划提问 (Planning questions)
- 最终增强 (Final enhancement)
- 优化历史 (Optimization history)

**Workbench:**
- 原始提示词 (Raw prompt)
- 上下文 (Context)
- 优化结果 (Optimization result)

**Architecture:**
- Frontend/backend separated modular monolith
- Unified model gateway calls published models
- Users cannot input model keys
- Models published by backend, users select from published list
- Display version vs actual call identifier recorded separately

**Identity module:**
- Platform roles vs workspace roles are two boundaries
- Workspace admin is NOT platform model config admin

**Context preparation & final enhancement:**
- Use short-term session numbers
- Sessions can expire

**History module:**
- Tenant + workspace scope
- Soft delete via deleted_at

**Migration history:**
- User-defined provider table was deprecated

**Error response:**
- Stable event codes + request identifiers only
- No complete logging platform config provided

**Roadmap (TODO, not implemented):**
- Team billing
- Browser plugin
- More third-party login

**Important notes:**
- Only standalone local runtime info available
- No multi-region or DR verification
- Design previews are visual sketches

Now let me draft the handover document. I need to be very careful about:
1. Citing materials for every claim
2. Not inferring things not in materials
3. Distinguishing implemented/planned/uncovered
4. The history retention conflict

Let me structure this properly. The document should be in Chinese since the task and materials are in Chinese.

Let me write this out:

---

# 技术交接文档

## 文档说明

本文档面向具备基础 Java 和 Vue 开发经验、但未参与过本项目的新维护人员。所有功能声明均附给定资料路径与对应证据。文档区分：
- **已实现**：给定资料中有明确证据
- **仅写在计划里**：资料中标记为待办/路线图/草稿
- **资料未覆盖**：给定资料中找不到依据

测试示例（materials/project_docs/tests/layout-example.txt）、旧版草稿（materials/project_docs/legacy-note.md）、正式说明（materials/project_docs/current-brief.md）分开标注。

---

## 1. 系统概览和模块职责表

### 1.1 技术栈概览

| 维度 | 内容 | 证据 |
|------|------|------|
| 架构形态 | 前后端分离的模块化单体 | materials/project_docs/current-brief.md |
| 前端框架 | Vue 3 + TypeScript | materials/project_docs/current-brief.md |
| 后端框架 | Java 21 + Spring Boot 3 | materials/project_docs/current-brief.md |
| 数据库 | PostgreSQL | materials/project_docs/current-brief.md |

### 1.2 模块职责表

| 模块/区域 | 职责描述 | 证据 |
|----------|----------|------|
| 统一模型网关 | 服务端通过该网关调用已发布模型 | materials/project_docs/current-brief.md |
| 模型发布 | 后台发布模型列表，普通用户只选择已发布条目 | materials/project_docs/current-brief.md |
| 模型版本记录 | 显示版本与实际调用标识分开记录 | materials/project_docs/current-brief.md |
| 工作台 | 提供原始提示词、上下文和优化结果三个区域 | rawPrompt / materials/project_docs/current-brief.md |
| history 模块 | 保留租户与工作区范围，删除历史采用 deleted_at 逻辑删除 | materials/project_docs/current-brief.md |
| 身份模块 | 平台角色与工作区角色是两个边界 | materials/project_docs/current-brief.md |
| api/controller | 仅描述协议入口 | materials/project_docs/current-brief.md |
| service 层 | 负责业务编排 | materials/project_docs/current-brief.md |

### 1.3 前端路由（资料明确列出）

| 路由 | 说明 | 证据 |
|------|------|------|
| 首页 | 路由清单中的一项 | materials/project_docs/current-brief.md |
| 工作台 | 路由清单中的一项 | materials/project_docs/current-brief.md |
| 历史 | 路由清单中的一项 | materials/project_docs/current-brief.md |
| 管理员 | 路由清单中的一项 | materials/project_docs/current-brief.md |

**注意**：资料未提供各路由的页面组件、守卫实现细节或路由懒加载配置等细节。详见第 3 节"未知清单"。

### 1.4 工作台三区域（基于 rawPrompt）

| 区域 | 说明 |
|------|------|
| 原始提示词 | 用户输入的初始提示词 |
| 上下文 | 上下文准备接口生成的会话上下文 |
| 优化结果 | 最终增强接口返回的优化结果 |

---

## 2. 实际接口与资料索引

### 2.1 合成当前接口清单（四组）

资料来源：materials/project_docs/current-brief.md

| 接口组 | 用途 | 会话特征 | 证据 |
|--------|------|----------|------|
| 上下文准备 | 为后续优化准备上下文 | 使用短时会话编号，会话可过期 | materials/project_docs/current-brief.md |
| 计划提问 | 在优化过程中计划提问 | 资料未说明是否使用会话编号 | materials/project_docs/current-brief.md |
| 最终增强 | 输出优化后的提示词 | 使用短时会话编号，会话可过期 | materials/project_docs/current-brief.md |
| 优化历史 | 记录与查询优化历史 | 历史记录含记录编号、摘要、模型版本、创建时间、状态 | materials/project_docs/current-brief.md |

### 2.2 优化历史记录字段

资料来源：materials/project_docs/current-brief.md

- 记录编号
- 摘要
- 模型版本
- 创建时间
- 状态

**重要**：资料明确"给定片段没有计费扣款实现，因此不能把历史记录解释为账单或支付凭证"。不要把历史记录视为计费凭证。

### 2.3 错误响应资料索引

资料来源：materials/project_docs/current-brief.md

错误响应只包含：
- 稳定事件代码
- 请求标识

**未覆盖**：完整日志平台配置、集中日志服务名称、检索方法。不得编造集中日志服务名称。

### 2.4 资料路径总览

| 资料类型 | 路径 | 用途 |
|---------|------|------|
| 正式说明 | materials/project_docs/current-brief.md | 现状与规划的主要依据 |
| 测试示例 | materials/project_docs/tests/layout-example.txt | 仅排版和来源识别，非事实 |
| 旧版草稿 | materials/project_docs/legacy-note.md | 历史保留相关，存在冲突 |

---

## 3. 现状、待办、未知及冲突清单

### 3.1 已实现（有资料证据）

| 功能 | 证据 |
|------|------|
| 前后端分离的模块化单体架构 | materials/project_docs/current-brief.md |
| Vue 3 + TypeScript 前端 | materials/project_docs/current-brief.md |
| Java 21 + Spring Boot 3 后端 | materials/project_docs/current-brief.md |
| PostgreSQL 数据库 | materials/project_docs/current-brief.md |
| 统一模型网关调用已发布模型 | materials/project_docs/current-brief.md |
| 用户不能输入模型密钥 | materials/project_docs/current-brief.md |
| 后台发布模型，普通用户选择已发布条目 | materials/project_docs/current-brief.md |
| 显示版本与实际调用标识分开记录 | materials/project_docs/current-brief.md |
| 工作台三区域（原始提示词、上下文、优化结果） | materials/project_docs/current-brief.md |
| 四组合成接口（上下文准备、计划提问、最终增强、优化历史） | materials/project_docs/current-brief.md |
| history 模块按租户与工作区范围保留 | materials/project_docs/current-brief.md |
| history 模块采用 deleted_at 逻辑删除 | materials/project_docs/current-brief.md |
| api/controller 仅作协议入口 | materials/project_docs/current-brief.md |
| service 层负责业务编排 | materials/project_docs/current-brief.md |
| 平台角色与工作区角色是两个边界 | materials/project_docs/current-brief.md |
| 上下文准备与最终增强使用短时会话编号 | materials/project_docs/current-brief.md |
| 会话可过期 | materials/project_docs/current-brief.md |
| 前端路由含首页、工作台、历史、管理员 | materials/project_docs/current-brief.md |

### 3.2 仅写在计划里（待办，未实现）

| 项目 | 证据 | 备注 |
|------|------|------|
| 团队计费 | materials/project_docs/current-brief.md "合成路线图中...团队计费...均标为待办" | 不得写为可用功能 |
| 浏览器插件 | materials/project_docs/current-brief.md 同上 | 不得写为可用功能 |
| 更多第三方登录 | materials/project_docs/current-brief.md 同上 | 不得写为可用功能 |

### 3.3 演进记录（历史结构，非当前功能）

| 项目 | 说明 | 证据 |
|------|------|------|
| 旧 migration 片段创建的用户自定义供应商表 | 已退役，不再使用 | materials/project_docs/current-brief.md |

**注**：此为演进历史，不放入当前管理员功能列表。

### 3.4 资料未覆盖（未知清单）

以下事项在给定资料中找不到依据，列出供核查入口使用：

| 未知项 | 影响 | 建议核查入口 |
|--------|------|--------------|
| 模型网关内部实现细节（鉴权、重试、限流） | 无法判断模型调用失败时的具体行为 | 源码中模型网关相关类与配置文件 |
| 短时会话编号的过期时间与会话存储实现 | 无法估算会话生命周期 | 上下文准备/最终增强接口实现与会话管理类 |
| 模型版本与调用标识的映射规则 | 无法解释版本显示差异 | 模型发布管理模块实现 |
| 前端路由守卫实现 | 管理员界面可见性与实际权限边界需另行核查 | 路由守卫相关前端源码 |
| 服务端权限校验细节 | 管理员界面可见性不是后端授权的替代 | 身份模块 service 层与拦截器/过滤器实现 |
| 错误契约完整定义 | 资料仅给出稳定事件代码和请求标识两项 | 统一错误响应封装类 |
| 优化历史完整字段集 | 资料列出 5 项，是否还有审计字段、用户归属等需核查 | 实体类与数据库 schema |
| history 模块是否包含跨租户查询接口 | 资料未明示 | Controller 与 Service 层接口 |
| 设计预览对应的实际页面 | 资料标注"目录中的设计预览属于视觉草图" | 仅用于视觉参考，不应据此寻找不存在的按钮 |
| 多区域部署与灾备方案 | 资料仅展示单机本地运行 | 见 3.5 部署覆盖边界 |

### 3.5 部署覆盖边界

| 维度 | 资料覆盖情况 | 证据 |
|------|--------------|------|
| 单机本地运行 | 有 | materials/project_docs/current-brief.md |
| 多区域部署 | 无 | materials/project_docs/current-brief.md "资料只展示单机本地运行信息，没有多区域部署和灾备验证" |
| 灾备验证 | 无 | 同上 |
| 高可用保证 | 无依据 | 不得给出没有依据的高可用保证 |

### 3.6 冲突清单

#### 3.6.1 历史记录保留期限（已澄清处理方式）

| 来源 | 主张 | 状态 |
|------|------|------|
| materials/project_docs/legacy-note.md（旧交接说明） | 历史永久保留 | 历史记录 |
| materials/project_docs/legacy-note.md（新需求草稿） | 历史保留 180 天 | 未批准 |

**处理方式（依据用户回答）**：
> 历史保留期限未批准且材料冲突，永久保留与180天并列核对，不擅自采用任何一项作为现状。

- 两份材料并列呈现，不擅自将其中任一项作为现状；
- 旧交接说明与新需求草稿均标注为旧版草稿，正式说明见 materials/project_docs/current-brief.md；
- 实际保留期限须以业务决策为准，本文档不预设答案；
- history 模块的 deleted_at 字段为逻辑删除机制，与保留期限无直接耦合。

#### 3.6.2 测试示例 vs 正式说明

| 来源 | 内容 |
|------|------|
| materials/project_docs/tests/layout-example.txt | 含"输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99%"等 |
| 性质 | 仅为排版和来源识别测试例子，非本题事实 |

**处理方式**：测试示例中的所有数值不得作为产品功能、性能指标或上线质量证据。文档不引用其中数据。

### 3.7 设计预览说明

资料来源：materials/project_docs/current-brief.md

> 目录中的设计预览属于视觉草图，不能当作已部署的界面截图。

接手人员在查阅设计稿时，应将其视为视觉草图而非已部署界面。不得据其寻找不存在的按钮或控件。

---

## 4. 权限边界与接手检查表

### 4.1 权限边界

资料来源：materials/project_docs/current-brief.md

#### 4.1.1 平台角色 vs 工作区角色

| 边界 | 用途 | 限制 |
|------|------|------|
| 平台角色 | 平台级管理能力 | — |
| 工作区角色 | 工作区内管理能力 | workspace 管理员不能视为全平台模型配置管理员 |

**说明**：身份模块材料明确二者是两个边界，不得将 workspace 管理员等同于平台级模型配置管理员。

#### 4.1.2 模型密钥管理

- 服务端通过统一模型网关调用已发布模型（materials/project_docs/current-brief.md）
- 用户不能输入模型密钥（同上）
- 模型列表由后台发布，普通用户只选择已发布条目（同上）
- 显示版本与实际调用标识分开记录（同上）

**重要约束**：交接材料不得建议浏览器存放 API Key。

#### 4.1.3 路由守卫与服务端权限校验

- 管理员界面可见性不是后端授权的替代（materials/project_docs/current-brief.md）
- 文档须区分路由守卫（前端） 与 服务端权限校验（后端）

### 4.2 模型配置管理边界

| 角色 | 模型配置权限 |
|------|--------------|
| 平台管理员（推断） | 全平台模型发布管理 |
| workspace 管理员 | 工作区内使用已发布模型，不能视为平台模型配置管理员 |

**注**：平台管理员的存在与具体能力资料未明示，仅由"模型列表由后台发布"推断存在后台发布能力。接手人员应通过源码核查实际角色定义。

### 4.3 接手检查表

按模块列出接手时需核查的项目：

#### 4.3.1 架构与基础

- [ ] 核对前后端分离的模块化单体结构
- [ ] 核对前端 Vue 3 + TypeScript、后端 Java 21 + Spring Boot 3、PostgreSQL 配置
- [ ] 确认模块划分与 service 层业务编排位置

#### 4.3.2 模型网关

- [ ] 定位统一模型网关实现类
- [ ] 核对模型发布流程与版本/调用标识映射
- [ ] 确认普通用户无法输入模型密钥

#### 4.3.3 工作台与接口

- [ ] 核对四组接口（上下文准备、计划提问、最终增强、优化历史）入口
- [ ] 核对工作台三区域（原始提示词、上下文、优化结果）前端组件
- [ ] 核对短时会话编号使用与过期机制
- [ ] 核对 api/controller 与 service 层职责划分

#### 4.3.4 history 模块

- [ ] 核对租户与工作区范围字段
- [ ] 核对 deleted_at 逻辑删除机制
- [ ] 核对优化历史字段（记录编号、摘要、模型版本、创建时间、状态）
- [ ] **核查当前历史保留期限的实际配置**（因材料冲突，见 3.6.1）

#### 4.3.5 权限与角色

- [ ] 区分平台角色与工作区角色实现
- [ ] 确认 workspace 管理员的权限边界
- [ ] 核对前端路由守卫实现
- [ ] 核对服务端权限校验（拦截器/过滤器/AOP 等）
- [ ] 核对管理员页面的可见性与后端授权关系

#### 4.3.6 错误处理与排查（只读）

> 本节内容依用户回答纳入：包含只读排查步骤，不含安装依赖、修改配置或启动生产服务的命令。

**可做的只读排查动作：**

| 排查步骤 | 操作 | 资料依据 |
|---------|------|---------|
| 关联稳定事件代码 | 在错误响应中读取稳定事件代码字段 | materials/project_docs/current-brief.md 错误响应含稳定事件代码 |
| 关联请求标识 | 在错误响应中读取请求标识字段 | 同上 |
| 检索相关日志条目 | 使用上述两个标识在本地日志中检索 | 资料未提供集中日志平台，不得编造服务名称 |

**禁止的排查动作（不在本文档中提供）：**

- 安装或升级依赖
- 修改配置文件
- 启动、停止或重启生产服务
- 执行数据库迁移
- 删除或覆盖任何文件

> 上述受限操作遵循平台通用安全边界，与本文档无关。

#### 4.3.7 部署与运行

- [ ] 确认当前仅有单机本地运行信息
- [ ] **不得承诺多区域部署或灾备能力**
- [ ] 核查实际部署拓扑是否超出资料覆盖范围

#### 4.3.8 演进记录

- [ ] 了解旧版"用户自定义供应商表"演进历史，知晓其已退役
- [ ] 该表不应作为当前管理员功能理解

#### 4.3.9 视觉草图

- [ ] 设计预览仅作视觉参考
- [ ] 不得据其寻找已部署界面中不存在的按钮

#### 4.3.10 待办项确认

- [ ] 团队计费、浏览器插件、更多第三方登录均为待办，不要写为可用功能
- [ ] 历史记录非账单或支付凭证

---

## 附录 A：资料引用约定

| 资料类型 | 路径 | 处理方式 |
|---------|------|---------|
| 正式说明 | materials/project_docs/current-brief.md | 作为现状与规划的主要依据 |
| 旧版草稿 | materials/project_docs/legacy-note.md | 视为旧材料，存在与新需求的冲突，须并列核对 |
| 测试示例 | materials/project_docs/tests/layout-example.txt | 仅作排版和来源识别，非本题事实 |

## 附录 B：禁止事项清单

- 不写测试夹具中的 99% 为线上测量值或产品承诺
- 不编造集中日志服务名称
- 不给出没有依据的高可用保证
- 不把设计预览当作已部署界面截图
- 不把 workspace 管理员写成全平台模型配置管理员
- 不建议浏览器存放 API Key
- 不写团队计费、浏览器插件、更多第三方登录为可用功能
- 不写历史记录为账单或支付凭证
- 不擅自采用"永久保留"或"180 天"作为现状
- 不把测试账号写为正式管理员
- 不在文档中包含密钥、完整认证标识或用户正文

---

Wait, I need to reconsider the structure and make sure I'm following all the rules. Let me review the requirements again:

1. ✅ 系统概览和模块职责表
2. ✅ 实际接口与资料索引
3. ✅ 现状、待办、未知及冲突清单
4. ✅ 权限边界与接手检查表

Let me also make sure:
- "用户回答" includes troubleshooting section as read-only only ✓
- History retention conflict noted, both presented ✓
- Material types distinguished ✓
- No fabricated facts ✓

Let me refine the document a bit more. I think I should also make sure the troubleshooting section is clearly labeled as conditional (user-confirmed) vs the rest of the doc.

Let me also re-read the requirement: "接手人员是否需要包含本地调试与故障排查操作章节当前尚未决定，不得默认补全。"

The user's answer said: "需要只读排查步骤，不包含安装依赖、修改配置或启动生产服务的命令。"

So I should include the troubleshooting section but explicitly mark it as read-only based on user confirmation.

One more thing - I need to make sure I'm being precise about citations. Let me double check everything.

Actually, looking at it again, I should clean up the document a bit more. Let me also be careful about "rawPrompt" - the task mentions this is a source. Let me re-read:

"工作台提供原始提示词、上下文和优化结果三个区域（来源：rawPrompt）"

So rawPrompt is a source - probably an earlier reference. I'll include it as a source.

Let me also re-check the user's clarified rules:
- 本次只整理现状与证据，不提出架构重写，不运行命令、不改源码 ✓
- 现状是前后端分离的模块化单体，服务端通过统一模型网关调用已发布模型，用户不能输入模型密钥；工作台提供原始提示词、上下文和优化结果三个区域 ✓
- 交接文档应准确区分已经实现、仅写在计划里、资料未覆盖的内容 ✓
- 合成目录中 history 模块保留租户与工作区范围，删除历史采用 deleted_at 逻辑删除 ✓
- 合成路线图中团队计费、浏览器插件和更多第三方登录均标为待办，不能写成可用功能 ✓
- 所有功能声明必须附给定资料路径与对应证据，没看到的父组件和调用方不得推断存在 ✓
- 历史迁移描述历史结构，不自动代表当前结构；当前接口清单优先用于现状说明，但冲突处必须先列明 ✓
- 交付文档不包含密钥、完整认证标识或用户正文，禁止把测试账号写为正式管理员 ✓
- 对于仍未明确的条件，不得默认补全 ✓
- 两份资料存在差异：旧交接说明写历史永久保留；新需求草稿写历史保留180天，但尚未批准 ✓
- 资料引用应能让接收任务的人找到对应依据，测试示例、旧版草稿和正式说明须分开 ✓
- 能由给定资料核查的常规细节，请自行整理；只有真正影响任务目标且目前缺失的业务决定才保留为待确认项 ✓
- 文字应紧扣本题，合并同一条件的重复解释，重要前提不能只留在页面提示中 ✓

And platform mandatory constraints:
- 明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实 ✓
- 不得在代码、日志或响应中泄露密码、Token、API Key 或私钥 ✓
- 输出应直接回应用户目标 ✓
- 禁止读取或输出受保护路径 ✓
- 删除或覆盖文件等需人工确认 ✓

I think the document is good. Let me finalize it.

Actually, I want to tighten it up and make sure the structure is clean. Let me also consolidate where I'm repeating things.

One thing I want to double-check: the user clarification said "需要只读排查步骤，不包含安装依赖、修改配置或启动生产服务的命令". So the troubleshooting section should explicitly:
1. Be marked as included based on user confirmation
2. Only contain read-only operations
3. NOT contain install, config change, or prod start commands

Let me finalize the document. I'll present it cleanly now.</think>

# 技术交接文档

> 适用读者：具备基础 Java 与 Vue 开发经验、未参与过本项目的新维护人员。
>
> 本文档仅整理现状与证据，不提出架构重写、不运行命令、不改源码。所有功能声明附给定资料路径与对应证据；找不到依据的能力列入未知清单并提供核查入口。
>
> 资料分类约定：
> - **正式说明**：`materials/project_docs/current-brief.md`
> - **旧版草稿**：`materials/project_docs/legacy-note.md`（含历史保留相关冲突口径）
> - **测试示例**：`materials/project_docs/tests/layout-example.txt`（仅排版和来源识别，非本题事实）

---

## 1. 系统概览和模块职责表

### 1.1 技术栈

| 维度 | 内容 | 证据 |
|---|---|---|
| 架构形态 | 前后端分离的模块化单体 | `materials/project_docs/current-brief.md` |
| 前端 | Vue 3 + TypeScript | `materials/project_docs/current-brief.md` |
| 后端 | Java 21 + Spring Boot 3 | `materials/project_docs/current-brief.md` |
| 数据库 | PostgreSQL | `materials/project_docs/current-brief.md` |
| 模型调用 | 服务端通过统一模型网关调用已发布模型 | `materials/project_docs/current-brief.md` |
| 模型密钥 | 用户不能输入模型密钥 | `materials/project_docs/current-brief.md` |
| 模型列表 | 由后台发布，普通用户只选择已发布条目 | `materials/project_docs/current-brief.md` |
| 版本记录 | 显示版本与实际调用标识分开记录 | `materials/project_docs/current-brief.md` |

### 1.2 模块职责

| 模块/区域 | 职责 | 证据 |
|---|---|---|
| 统一模型网关 | 集中对外调用已发布模型 | `materials/project_docs/current-brief.md` |
| 工作台 | 提供原始提示词、上下文、优化结果三个区域 | `rawPrompt` |
| history 模块 | 保留租户与工作区范围；删除历史采用 `deleted_at` 逻辑删除 | `materials/project_docs/current-brief.md` |
| 身份模块 | 平台角色与工作区角色是两个边界 | `materials/project_docs/current-brief.md` |
| `api/controller` 目录 | 仅描述协议入口（业务编排不在此层） | `materials/project_docs/current-brief.md` |
| `service` 层 | 负责业务编排 | `materials/project_docs/current-brief.md` |

### 1.3 前端路由（资料明示）

| 路由 | 证据 |
|---|---|
| 首页 | `materials/project_docs/current-brief.md` |
| 工作台 | `materials/project_docs/current-brief.md` |
| 历史 | `materials/project_docs/current-brief.md` |
| 管理员 | `materials/project_docs/current-brief.md` |

> 资料未提供各路由的页面组件、守卫实现细节、懒加载配置等；如需了解，参见第 3.3 节"未知清单"。

### 1.4 工作台三区域

| 区域 | 用途 | 证据 |
|---|---|---|
| 原始提示词 | 用户输入的初始提示词 | `rawPrompt` |
| 上下文 | 由"上下文准备"接口生成的会话上下文 | `materials/project_docs/current-brief.md` |
| 优化结果 | 由"最终增强"接口返回的优化结果 | `materials/project_docs/current-brief.md` |

---

## 2. 实际接口与资料索引

### 2.1 合成当前接口清单（四组）

证据：`materials/project_docs/current-brief.md`

| 接口组 | 用途 | 会话特征 | 备注 |
|---|---|---|---|
| 上下文准备 | 为后续优化准备上下文 | 短时会话编号，会话可过期 | 不得自行补全材料未提供的全局缓存清理实现 |
| 计划提问 | 在优化过程中计划提问 | 资料未说明是否使用会话编号 | 字段与会话语义见 3.3 未知清单 |
| 最终增强 | 输出优化后的提示词 | 短时会话编号，会话可过期 | 同上 |
| 优化历史 | 记录与查询优化历史 | 历史范围=租户+工作区 | 给定片段没有计费扣款实现 |

### 2.2 优化历史记录字段

证据：`materials/project_docs/current-brief.md`

- 记录编号
- 摘要
- 模型版本
- 创建时间
- 状态

**重要**：资料明示历史记录**不是**账单或支付凭证，不得用于计费扣款。

### 2.3 错误响应

证据：`materials/project_docs/current-brief.md`

错误响应包含两项：
- 稳定事件代码
- 请求标识

> 资料未提供完整日志平台配置。不得编造集中日志服务名称。

### 2.4 资料路径总览

| 类型 | 路径 | 用途边界 |
|---|---|---|
| 正式说明 | `materials/project_docs/current-brief.md` | 现状与规划的主要依据 |
| 旧版草稿 | `materials/project_docs/legacy-note.md` | 历史保留口径冲突来源，须并列核对 |
| 测试示例 | `materials/project_docs/tests/layout-example.txt` | 排版与来源识别；其中的 CSV / Python / 100 万元 / 99% 仅为夹具，不得作为功能、性能、上线质量或事实引用 |

---

## 3. 现状、待办、未知及冲突清单

### 3.1 已实现（有资料证据）

| 功能 | 证据 |
|---|---|
| 前后端分离的模块化单体 | `materials/project_docs/current-brief.md` |
| Vue 3 + TypeScript 前端 | `materials/project_docs/current-brief.md` |
| Java 21 + Spring Boot 3 后端 | `materials/project_docs/current-brief.md` |
| PostgreSQL 数据库 | `materials/project_docs/current-brief.md` |
| 统一模型网关调用已发布模型 | `materials/project_docs/current-brief.md` |
| 用户不能输入模型密钥 | `materials/project_docs/current-brief.md` |
| 模型列表后台发布、用户仅选择已发布条目 | `materials/project_docs/current-brief.md` |
| 显示版本与实际调用标识分开记录 | `materials/project_docs/current-brief.md` |
| 工作台三区域（原始提示词、上下文、优化结果） | `rawPrompt` |
| 四组接口（上下文准备、计划提问、最终增强、优化历史） | `materials/project_docs/current-brief.md` |
| history 按租户与工作区范围保留 | `materials/project_docs/current-brief.md` |
| history 采用 `deleted_at` 逻辑删除 | `materials/project_docs/current-brief.md` |
| `api/controller` 仅作协议入口 | `materials/project_docs/current-brief.md` |
| `service` 层负责业务编排 | `materials/project_docs/current-brief.md` |
| 平台角色与工作区角色是两个边界 | `materials/project_docs/current-brief.md` |
| 上下文准备 / 最终增强使用短时会话编号 | `materials/project_docs/current-brief.md` |
| 短时会话可过期 | `materials/project_docs/current-brief.md` |
| 前端路由含首页、工作台、历史、管理员 | `materials/project_docs/current-brief.md` |

### 3.2 仅写在计划里（待办，未实现）

| 项目 | 证据 | 注意事项 |
|---|---|---|
| 团队计费 | `materials/project_docs/current-brief.md`「合成路线图中…均标为待办」 | 不得写为可用功能 |
| 浏览器插件 | 同上 | 不得写为可用功能 |
| 更多第三方登录 | 同上 | 不得写为可用功能 |

### 3.3 演进记录（历史结构，非当前功能）

| 项目 | 说明 | 证据 |
|---|---|---|
| 旧 migration 片段创建的用户自定义供应商表 | 已退役，不再使用 | `materials/project_docs/current-brief.md` |

> 该项为演进历史，**不应**放入当前管理员功能列表。

### 3.4 资料未覆盖（未知清单 + 核查入口）

资料中找不到直接证据的事项，列出供接手人员定向核查：

| 未知项 | 影响 | 建议核查入口 |
|---|---|---|
| 模型网关内部实现（鉴权、重试、限流、降级） | 无法判断模型调用失败时的具体行为 | 模型网关相关类与配置 |
| 短时会话编号的过期时间与会话存储实现 | 无法估算会话生命周期 | 上下文准备/最终增强接口与会话管理类 |
| 模型版本与调用标识的映射规则 | 无法解释显示版本差异 | 模型发布管理模块 |
| 前端路由守卫实现细节 | 管理员界面可见性需另行核查 | 路由守卫相关前端源码 |
| 服务端权限校验实现 | 管理员界面可见性不是后端授权替代 | 身份模块 service 层、拦截器/过滤器/AOP |
| 错误契约完整定义 | 资料仅给出稳定事件代码和请求标识 | 统一错误响应封装类 |
| 优化历史完整字段集 | 资料列出 5 项，是否含审计字段、用户归属等需核查 | 历史实体类与数据库 schema |
| history 是否包含跨租户查询接口 | 资料未明示 | Controller 与 Service 接口 |
| 设计预览对应的实际页面 | 资料标注"视觉草图" | 不得据此寻找不存在的按钮 |
| 多区域部署与灾备方案 | 见 3.5 | — |

> 单 Controller 文件不能等同于全量错误处理覆盖；判定"所有模型错误都被处理"前需找到统一错误契约证据（`materials/project_docs/current-brief.md`）。

### 3.5 部署覆盖边界

| 维度 | 资料覆盖情况 | 证据 |
|---|---|---|
| 单机本地运行 | 有 | `materials/project_docs/current-brief.md` |
| 多区域部署 | 无 | `materials/project_docs/current-brief.md`「资料只展示单机本地运行信息，没有多区域部署和灾备验证」 |
| 灾备验证 | 无 | 同上 |
| 高可用保证 | 无依据 | **不得**给出没有依据的高可用承诺 |

### 3.6 冲突清单

#### 3.6.1 历史记录保留期限（已按用户回答处理）

| 来源 | 主张 | 状态 |
|---|---|---|
| `materials/project_docs/legacy-note.md`（旧交接说明） | 历史永久保留 | 旧版草稿 |
| `materials/project_docs/legacy-note.md`（新需求草稿） | 历史保留 180 天 | 未批准 |

**处理方式（用户回答）**：
> 历史保留期限未批准且材料冲突，永久保留与 180 天并列核对，不擅自采用任何一项作为现状。

- 两份材料并列呈现，不擅自将其中任一项当作现状；
- 两份均归类为旧版草稿；
- 实际保留期限须由业务决策确认；
- `history` 模块的 `deleted_at` 是逻辑删除机制，与保留期限无直接耦合。

#### 3.6.2 测试示例 vs 正式说明

`materials/project_docs/tests/layout-example.txt` 含「输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99%」等。该文件已标注为排版和来源识别测试例子，**不是**本题事实：
- 99% 为断言夹具，**不是**线上测量值，也**不是**产品承诺，不得用于证明上线质量；
- CSV / Python / 100 万元等内容**不得**作为产品功能、收入或工具偏好引用。

#### 3.7 设计预览使用边界

证据：`materials/project_docs/current-brief.md`

> 目录中的设计预览属于视觉草图，不能当作已部署的界面截图。

接手人员查阅设计稿时，应将其视为视觉草图，**不得**据此寻找已部署界面中不存在的按钮或控件。

---

## 4. 权限边界与接手检查表

### 4.1 权限边界

证据：`materials/project_docs/current-brief.md`

#### 4.1.1 平台角色 vs 工作区角色

| 边界 | 用途 | 限制 |
|---|---|---|
| 平台角色 | 平台级能力 | — |
| 工作区角色 | 工作区内能力 | workspace 管理员**不能**视为全平台模型配置管理员 |

#### 4.1.2 模型密钥

- 服务端通过统一模型网关调用已发布模型；
- 用户**不能**输入模型密钥；
- 模型列表由后台发布，普通用户只选择已发布条目；
- 显示版本与实际调用标识分开记录；
- **交接材料不得建议浏览器存放 API Key**。

#### 4.1.3 路由守卫与服务端权限校验

- 管理员界面可见性**不是**后端授权的替代；
- 必须区分：前端路由守卫 vs 后端权限校验；
- 接手人员应在源码中分别确认两侧实现。

### 4.2 接手检查表

#### 4.2.1 架构与基础

- [ ] 核对前后端分离的模块化单体结构
- [ ] 核对前端 Vue 3 + TypeScript、后端 Java 21 + Spring Boot 3、PostgreSQL 配置
- [ ] 确认模块划分与 service 层业务编排位置

#### 4.2.2 模型网关

- [ ] 定位统一模型网关实现类
- [ ] 