# 技术交接文档（现状与证据版）

> 适用范围：本文件只整理给定资料中可核对的现状与证据，不提出架构重写，不运行命令，不改源码。所有功能声明均附资料路径与证据；未在给定资料中出现的父组件、调用方、部署形态一律不推断存在。
>
> 资料分层标注约定：
> - 【正式说明】= `materials/project_docs/current-brief.md`
> - 【旧版草稿】= `materials/project_docs/legacy-note.md`
> - 【测试示例】= `materials/project_docs/tests/layout-example.txt`（仅排版/来源识别用，不是本题事实）

---

## 1. 系统概览和模块职责表

### 1.1 系统概览（均为【正式说明】）

- 形态：前后端分离的模块化单体。
  - 证据：`materials/project_docs/current-brief.md`（“前后端分离的模块化单体”）。
- 前端：Vue 3 + TypeScript。
  - 证据：`materials/project_docs/current-brief.md`（“当前前端使用 Vue 3 和 TypeScript”）。
- 后端：Java 21 + Spring Boot 3。
  - 证据：`materials/project_docs/current-brief.md`（“后端使用 Java 21 和 Spring Boot 3”）。
- 数据库：PostgreSQL。
  - 证据：`materials/project_docs/current-brief.md`（“数据库为 PostgreSQL”）。
- 模型调用方式：服务端通过统一模型网关调用已发布模型；用户不能输入模型密钥；模型列表由后台发布，普通用户只选择已发布条目；显示版本与实际调用标识分开记录。
  - 证据：`materials/project_docs/current-brief.md`（“服务端通过统一模型网关调用已发布模型，用户不能输入模型密钥”“模型列表由后台发布，普通用户只选择已发布条目”“显示版本与实际调用标识分开记录”）。
- 工作台区域：原始提示词、上下文、优化结果三个区域。
  - 证据：`materials/project_docs/current-brief.md`（“工作台提供原始提示词、上下文和优化结果三个区域”）。
- 运行信息覆盖边界：资料只展示单机本地运行信息，没有多区域部署和灾备验证。
  - 证据：`materials/project_docs/current-brief.md`（“资料只展示单机本地运行信息，没有多区域部署和灾备验证”）。
  - 结论：不得给出高可用/灾备保证。

### 1.2 模块职责表

| 模块/层 | 职责（资料口径） | 证据路径 | 状态标注 |
|---|---|---|---|
| api/controller | 只描述协议入口 | `materials/project_docs/current-brief.md`（“目录 api/controller 只描述协议入口”） | 已实现（结构说明） |
| service 层 | 业务编排 | `materials/project_docs/current-brief.md`（“业务编排由 service 层负责”） | 已实现（结构说明） |
| history 模块 | 保留租户与工作区范围；删除历史采用 `deleted_at` 逻辑删除 | `materials/project_docs/current-brief.md`（“history 模块保留租户与工作区范围，删除历史采用 deleted_at 逻辑删除”） | 已实现（结构说明） |
| 身份模块 | 平台角色与工作区角色是两个边界 | `materials/project_docs/current-brief.md`（“身份模块材料表明平台角色与工作区角色是两个边界”） | 已实现（结构说明） |
| 模型网关 | 服务端统一调用已发布模型；用户不能输入模型密钥 | `materials/project_docs/current-brief.md` | 已实现（结构说明） |
| 前端路由 | 首页、工作台、历史、管理员页面 | `materials/project_docs/current-brief.md`（“前端路由清单包含首页、工作台、历史和管理员页面”） | 已实现（路由清单） |
| 设计预览 | 视觉草图 | `materials/project_docs/current-brief.md`（“目录中的设计预览属于视觉草图”） | 草图，非部署界面 |

> 说明：上表“已实现”仅指资料明确写出的结构/清单事实，不代表该模块全部行为已验证。资料未覆盖的父组件、调用方、具体实现细节不在此表推断。

---

## 2. 实际接口与资料索引

### 2.1 接口分组（【正式说明】）

合成当前接口清单包含四组接口：

| 组 | 名称 | 证据路径 |
|---|---|---|
| 1 | 上下文准备 | `materials/project_docs/current-brief.md`（“合成当前接口清单包含上下文准备、计划提问、最终增强和优化历史四组接口”） |
| 2 | 计划提问 | 同上 |
| 3 | 最终增强 | 同上 |
| 4 | 优化历史 | 同上 |

### 2.2 接口相关约束与索引

| 主题 | 资料口径 | 证据路径 |
|---|---|---|
| 协议入口 vs 业务编排 | controller 只描述协议入口，业务编排在 service 层 | `materials/project_docs/current-brief.md` |
| 错误契约 | 错误响应只有稳定事件代码和请求标识；未提供完整日志平台配置 | `materials/project_docs/current-brief.md` |
| 短时会话 | 上下文准备与最终增强使用短时会话编号，会话可过期 | `materials/project_docs/current-brief.md` |
| 优化历史字段 | 记录编号、摘要、模型版本、创建时间、状态 | `materials/project_docs/current-brief.md` |
| 历史删除 | `deleted_at` 逻辑删除 | `materials/project_docs/current-brief.md` |
| 模型版本记录 | 显示版本与实际调用标识分开记录 | `materials/project_docs/current-brief.md` |

### 2.3 未证实能力的核查入口

以下能力在给定资料中未证实，接手人员应按“核查入口”自行核对，而不是默认存在：

| 未证实能力 | 核查入口（资料路径/目录） | 说明 |
|---|---|---|
| 所有模型错误是否被统一处理 | `materials/project_docs/current-brief.md`（“不能根据一个 Controller 文件就宣称所有模型错误都被处理，需要找到统一错误契约证据”） | 需找到统一错误契约证据 |
| 全局缓存清理实现 | `materials/project_docs/current-brief.md`（“不给出材料未提供的全局缓存清理实现”） | 资料未提供 |
| 完整日志平台配置 | `materials/project_docs/current-brief.md`（“未提供完整日志平台配置”） | 不得编造集中日志服务名称 |
| 多区域部署/灾备 | `materials/project_docs/current-brief.md`（“没有多区域部署和灾备验证”） | 不得给出高可用保证 |
| 计费/扣款实现 | `materials/project_docs/current-brief.md`（“给定片段没有计费扣款实现”） | 历史记录不得解释为账单或支付凭证 |
| 父组件与调用方 | 给定资料未出现 | 不得推断存在 |

---

## 3. 现状、待办、未知及冲突清单

### 3.1 现状（已实现/已明确，【正式说明】）

- 前后端分离的模块化单体；Vue 3 + TypeScript；Java 21 + Spring Boot 3；PostgreSQL。
  - 证据：`materials/project_docs/current-brief.md`。
- 服务端通过统一模型网关调用已发布模型；用户不能输入模型密钥；模型列表由后台发布，普通用户只选择已发布条目；显示版本与实际调用标识分开记录。
  - 证据：`materials/project_docs/current-brief.md`。
- 工作台提供原始提示词、上下文和优化结果三个区域。
  - 证据：`materials/project_docs/current-brief.md`。
- 当前接口清单四组：上下文准备、计划提问、最终增强、优化历史。
  - 证据：`materials/project_docs/current-brief.md`。
- history 模块保留租户与工作区范围；删除历史采用 `deleted_at` 逻辑删除。
  - 证据：`materials/project_docs/current-brief.md`。
- 身份模块：平台角色与工作区角色是两个边界。
  - 证据：`materials/project_docs/current-brief.md`。
- 上下文准备与最终增强使用短时会话编号，会话可过期。
  - 证据：`materials/project_docs/current-brief.md`。
- 前端路由清单：首页、工作台、历史、管理员页面。
  - 证据：`materials/project_docs/current-brief.md`。
- 错误响应只有稳定事件代码和请求标识。
  - 证据：`materials/project_docs/current-brief.md`。
- 设计预览属于视觉草图。
  - 证据：`materials/project_docs/current-brief.md`。
- 运行信息仅单机本地，无多区域部署和灾备验证。
  - 证据：`materials/project_docs/current-brief.md`。

### 3.2 待办（仅写在计划里，【正式说明】）

| 待办项 | 证据路径 | 约束 |
|---|---|---|
| 团队计费 | `materials/project_docs/current-brief.md`（“合成路线图中团队计费、浏览器插件和更多第三方登录均标为待办”） | 不得写成可用功能 |
| 浏览器插件 | 同上 | 不得写成可用功能 |
| 更多第三方登录 | 同上 | 不得写成可用功能 |

### 3.3 未知（资料未覆盖）

- 完整日志平台配置：资料未提供。
  - 证据：`materials/project_docs/current-brief.md`（“未提供完整日志平台配置”）。
- 全局缓存清理实现：资料未提供。
  - 证据：`materials/project_docs/current-brief.md`（“不给出材料未提供的全局缓存清理实现”）。
- 多区域部署与灾备验证：资料未覆盖。
  - 证据：`materials/project_docs/current-brief.md`（“没有多区域部署和灾备验证”）。
- 计费/扣款实现：给定片段没有。
  - 证据：`materials/project_docs/current-brief.md`（“给定片段没有计费扣款实现”）。
- 父组件与调用方：给定资料未出现，不得推断存在。
  - 证据：任务约束与 `materials/project_docs/current-brief.md`（“没看到的父组件和调用方不得推断存在”）。

### 3.4 冲突清单（必须先列明）

| 冲突主题 | 一方口径 | 另一方口径 | 证据路径 | 处理方式 |
|---|---|---|---|---|
| 历史保留期限 | 旧交接说明：历史永久保留 | 新需求草稿：历史保留 180 天，但尚未批准 | `materials/project_docs/legacy-note.md` | 两者并列核对，不擅自采用任何一项作为现状 |
| 历史迁移 vs 当前结构 | 旧 migration 片段创建了用户自定义供应商表 | 后续退役记录明确不再使用 | `materials/project_docs/current-brief.md` | 历史迁移描述历史结构，不自动代表当前结构；当前接口清单优先用于现状说明 |
| 接口清单 vs 实现细节 | 当前接口清单四组接口 | 资料未提供完整实现细节 | `materials/project_docs/current-brief.md` | 以当前接口清单为现状说明，冲突处先列明 |

> 说明：历史保留期限冲突属于“真正影响任务目标且目前缺失的业务决定”，保留为待确认项，不默认补全。

### 3.5 测试示例、旧版草稿、正式说明分开标注

- 【正式说明】`materials/project_docs/current-brief.md`：用于现状、接口、边界、待办、未知。
- 【旧版草稿】`materials/project_docs/legacy-note.md`：旧交接说明写历史永久保留；新需求草稿写历史保留 180 天但尚未批准。仅用于冲突列明，不作为现状依据。
- 【测试示例】`materials/project_docs/tests/layout-example.txt`：输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99% 仅为排版和来源识别测试例子，不是本题事实，不代表已实现或取得成果，不能作为线上测量值或产品承诺。

---

## 4. 权限边界与接手检查表

### 4.1 权限边界（【正式说明】）

- 平台角色与工作区角色是两个边界。
  - 证据：`materials/project_docs/current-brief.md`（“身份模块材料表明平台角色与工作区角色是两个边界”）。
- 不能把 workspace 管理员写成全平台模型配置管理员。
  - 证据：`materials/project_docs/current-brief.md`（“不能把 workspace 管理员写成全平台模型配置管理员”）。
- 模型列表由后台发布，普通用户只选择已发布条目。
  - 证据：`materials/project_docs/current-brief.md`。
- 用户不能输入模型密钥；交接材料不能建议浏览器存放 API Key。
  - 证据：`materials/project_docs/current-brief.md`（“用户不能输入模型密钥”“交接材料不能建议浏览器存放 API Key”）。
- 管理员界面可见性不是后端授权的替代；应区分路由守卫与服务端权限校验。
  - 证据：`materials/project_docs/current-brief.md`（“管理员界面可见性不是后端授权的替代，文档应区分路由守卫与服务端权限校验”）。
- 历史记录不得解释为账单或支付凭证。
  - 证据：`materials/project_docs/current-brief.md`（“给定片段没有计费扣款实现，因此不能把历史记录解释为账单或支付凭证”）。

### 4.2 接手检查表

| 检查项 | 核查入口 | 预期证据 | 状态 |
|---|---|---|---|
| 确认当前接口四组 | `materials/project_docs/current-brief.md` | 上下文准备、计划提问、最终增强、优化历史 | 已明确 |
| 确认 history 逻辑删除 | `materials/project_docs/current-brief.md` | `deleted_at` | 已明确 |
| 确认租户与工作区范围 | `materials/project_docs/current-brief.md` | history 模块保留租户与工作区范围 | 已明确 |
| 确认平台角色 vs 工作区角色 | `materials/project_docs/current-brief.md` | 两个边界 | 已明确 |
| 确认模型网关调用方式 | `materials/project_docs/current-brief.md` | 服务端统一网关，用户不能输入密钥 | 已明确 |
| 确认显示版本与实际调用标识分开 | `materials/project_docs/current-brief.md` | 分开记录 | 已明确 |
| 确认短时会话可过期 | `materials/project_docs/current-brief.md` | 上下文准备与最终增强使用短时会话编号 | 已明确 |
| 确认错误响应字段 | `materials/project_docs/current-brief.md` | 稳定事件代码 + 请求标识 | 已明确 |
| 确认前端路由清单 | `materials/project_docs/current-brief.md` | 首页、工作台、历史、管理员 | 已明确 |
| 确认设计预览性质 | `materials/project_docs/current-brief.md` | 视觉草图，非部署截图 | 已明确 |
| 确认运行覆盖边界 | `materials/project_docs/current-brief.md` | 单机本地，无多区域/灾备 | 已明确 |
| 核查统一错误契约证据 | `materials/project_docs/current-brief.md` | 需找到统一错误契约证据 | 待核查 |
| 核查全局缓存清理实现 | `materials/project_docs/current-brief.md` | 资料未提供 | 待核查 |
| 核查完整日志平台配置 | `materials/project_docs/current-brief.md` | 资料未提供 | 待核查 |
| 核查计费/扣款实现 | `materials/project_docs/current-brief.md` | 给定片段没有 | 待核查 |
| 核查父组件与调用方 | 给定资料未出现 | 不得推断存在 | 待核查 |
| 历史保留期限冲突 | `materials/project_docs/legacy-note.md` | 永久保留 vs 180 天未批准 | 待确认 |
| 本地调试与故障排查操作章节 | 当前尚未决定 | 不得默认补全 | 待确认 |

### 4.3 排查说明（不编造日志服务名称）

- 错误响应只有稳定事件代码和请求标识；排查时可说明如何关联稳定事件代码和请求标识。
  - 证据：`materials/project_docs/current-brief.md`（“错误响应只有稳定事件代码和请求标识”“排查章节可以说明如何关联标识，但不能编造集中日志服务名称”）。
- 不得编造集中日志服务名称。
  - 证据：`materials/project_docs/current-brief.md`。

### 4.4 安全与合规约束

- 交付文档不包含密钥、完整认证标识或用户正文。
  - 证据：任务约束。
- 禁止把测试账号写为正式管理员。
  - 证据：任务约束。
- 不得建议浏览器存放 API Key。
  - 证据：`materials/project_docs/current-brief.md`。
- 不得把合成路线图中团队计费、浏览器插件和更多第三方登录写成可用功能。
  - 证据：`materials/project_docs/current-brief.md`。
- 不得把测试夹具中的 99% 写成线上测量值或产品承诺。
  - 证据：`materials/project_docs/tests/layout-example.txt` 与 `materials/project_docs/current-brief.md`。
- 不得给出没有依据的高可用保证。
  - 证据：`materials/project_docs/current-brief.md`。
- 不得把设计预览当作已部署界面截图。
  - 证据：`materials/project_docs/current-brief.md`。

---

## 5. 待确认项（仅保留真正影响任务目标且目前缺失的业务决定）

1. 历史保留期限：旧交接说明写永久保留；新需求草稿写历史保留 180 天但尚未批准。两者并列核对，不擅自采用任何一项作为现状。
   - 证据：`materials/project_docs/legacy-note.md`。
2. 接手人员是否需要包含本地调试与故障排查操作章节：当前尚未决定，不得默认补全。
   - 证据：任务约束。

> 除上述两项外，能由给定资料核查的常规细节已在本文档中整理，不将整篇文档变成待确认问卷。