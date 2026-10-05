# 技术交接文档（现状与证据版）

> 适用范围：本文件只整理给定资料中可核对的现状与证据，不提出架构重写，不运行命令，不改源码。资料引用路径均以 `materials/project_docs/` 为根。测试示例、旧版草稿与正式说明在文中分别标注。

---

## 1. 系统概览和模块职责表

### 1.1 系统概览

- 形态：前后端分离的模块化单体（来源：`materials/project_docs/current-brief.md`）。
- 前端：Vue 3 + TypeScript（来源：`materials/project_docs/current-brief.md`）。
- 后端：Java 21 + Spring Boot 3（来源：`materials/project_docs/current-brief.md`）。
- 数据库：PostgreSQL（来源：`materials/project_docs/current-brief.md`）。
- 模型调用：服务端通过统一模型网关调用已发布模型；用户不能输入模型密钥；模型列表由后台发布，普通用户只选择已发布条目；显示版本与实际调用标识分开记录（来源：`materials/project_docs/current-brief.md`）。
- 工作台区域：原始提示词、上下文、优化结果三个区域（来源：`materials/project_docs/current-brief.md`，rawPrompt）。
- 前端路由清单：首页、工作台、历史、管理员页面（来源：`materials/project_docs/current-brief.md`）。
- 运行信息覆盖边界：资料只展示单机本地运行信息，没有多区域部署和灾备验证（来源：`materials/project_docs/current-brief.md`）。

### 1.2 模块职责表

| 模块/目录 | 职责（依据资料） | 证据路径 | 状态标注 |
|---|---|---|---|
| `api/controller` | 只描述协议入口，业务编排由 service 层负责 | `materials/project_docs/current-brief.md` | 现状（正式说明） |
| service 层 | 承担业务编排 | `materials/project_docs/current-brief.md` | 现状（正式说明） |
| history 模块 | 保留租户与工作区范围；删除历史采用 `deleted_at` 逻辑删除 | `materials/project_docs/current-brief.md` | 现状（正式说明） |
| 身份模块 | 平台角色与工作区角色是两个边界 | `materials/project_docs/current-brief.md` | 现状（正式说明） |
| 模型网关 | 服务端统一调用已发布模型；用户不能输入模型密钥 | `materials/project_docs/current-brief.md` | 现状（正式说明） |
| 前端路由 | 首页、工作台、历史、管理员页面 | `materials/project_docs/current-brief.md` | 现状（正式说明） |
| 设计预览目录 | 属于视觉草图，不是已部署界面截图 | `materials/project_docs/current-brief.md` | 现状（正式说明，用途限定） |
| 旧 migration 片段 | 创建过用户自定义供应商表，后续退役记录明确不再使用 | `materials/project_docs/current-brief.md` | 历史结构（演进记录，不代表当前结构） |
| 合成路线图 | 团队计费、浏览器插件、更多第三方登录均标为待办 | `materials/project_docs/current-brief.md` | 计划（待办，不可写成可用功能） |

> 说明：表中未列出的父组件、调用方、统一错误处理实现等，资料未覆盖，不得推断存在。

---

## 2. 实际接口与资料索引

### 2.1 接口分组（依据当前接口清单）

合成当前接口清单包含四组接口（来源：`materials/project_docs/current-brief.md`）：

1. 上下文准备
2. 计划提问
3. 最终增强
4. 优化历史

### 2.2 接口与资料索引表

| 接口组 | 资料给出的关键事实 | 证据路径 | 备注 |
|---|---|---|---|
| 上下文准备 | 使用短时会话编号；会话可过期 | `materials/project_docs/current-brief.md` | 未提供全局缓存清理实现 |
| 计划提问 | 属于当前接口清单四组之一 | `materials/project_docs/current-brief.md` | 资料未给出更细字段 |
| 最终增强 | 使用短时会话编号；会话可过期 | `materials/project_docs/current-brief.md` | 未提供全局缓存清理实现 |
| 优化历史 | 清单包含记录编号、摘要、模型版本、创建时间、状态；保留租户与工作区范围；删除采用 `deleted_at` 逻辑删除 | `materials/project_docs/current-brief.md` | 给定片段没有计费扣款实现，不得解释为账单或支付凭证 |

### 2.3 错误响应与排查入口

- 资料给出的错误响应只有稳定事件代码和请求标识（来源：`materials/project_docs/current-brief.md`）。
- 资料未提供完整日志平台配置；不得编造集中日志服务名称（来源：`materials/project_docs/current-brief.md`）。
- 排查时可依据稳定事件代码与请求标识进行关联（来源：`materials/project_docs/current-brief.md`）。

### 2.4 未证实能力的核查入口

| 未证实能力 | 核查入口（依据资料可核对的位置） | 证据路径 |
|---|---|---|
| 统一模型错误处理是否覆盖所有 Controller | 需找到统一错误契约证据；不能仅凭单个 Controller 文件宣称 | `materials/project_docs/current-brief.md` |
| 全局缓存清理实现 | 资料未提供；需在源码/目录中核对是否存在 | `materials/project_docs/current-brief.md` |
| 完整日志平台配置 | 资料未提供；需在配置与源码中核对 | `materials/project_docs/current-brief.md` |
| 多区域部署与灾备 | 资料只展示单机本地运行信息 | `materials/project_docs/current-brief.md` |
| 管理员界面按钮与布局 | 设计预览为视觉草图，不能当作已部署截图 | `materials/project_docs/current-brief.md` |

---

## 3. 现状、待办、未知及冲突清单

### 3.1 现状（已实现/已明确）

| 项 | 依据 | 证据路径 |
|---|---|---|
| 前后端分离的模块化单体 | 正式说明 | `materials/project_docs/current-brief.md` |
| Vue 3 + TypeScript 前端 | 正式说明 | `materials/project_docs/current-brief.md` |
| Java 21 + Spring Boot 3 后端 | 正式说明 | `materials/project_docs/current-brief.md` |
| PostgreSQL 数据库 | 正式说明 | `materials/project_docs/current-brief.md` |
| 服务端统一模型网关调用已发布模型 | 正式说明 | `materials/project_docs/current-brief.md` |
| 用户不能输入模型密钥 | 正式说明 | `materials/project_docs/current-brief.md` |
| 模型列表由后台发布，普通用户只选择已发布条目 | 正式说明 | `materials/project_docs/current-brief.md` |
| 显示版本与实际调用标识分开记录 | 正式说明 | `materials/project_docs/current-brief.md` |
| 工作台三区域：原始提示词、上下文、优化结果 | 正式说明（rawPrompt） | `materials/project_docs/current-brief.md` |
| 当前接口清单四组：上下文准备、计划提问、最终增强、优化历史 | 正式说明 | `materials/project_docs/current-brief.md` |
| history 模块保留租户与工作区范围 | 正式说明 | `materials/project_docs/current-brief.md` |
| 删除历史采用 `deleted_at` 逻辑删除 | 正式说明 | `materials/project_docs/current-brief.md` |
| 平台角色与工作区角色是两个边界 | 正式说明 | `materials/project_docs/current-brief.md` |
| 上下文准备与最终增强使用短时会话编号，会话可过期 | 正式说明 | `materials/project_docs/current-brief.md` |
| 前端路由：首页、工作台、历史、管理员页面 | 正式说明 | `materials/project_docs/current-brief.md` |
| 错误响应含稳定事件代码与请求标识 | 正式说明 | `materials/project_docs/current-brief.md` |
| 设计预览属于视觉草图 | 正式说明 | `materials/project_docs/current-brief.md` |

### 3.2 待办（仅写在计划里）

| 项 | 依据 | 证据路径 |
|---|---|---|
| 团队计费 | 合成路线图标为待办 | `materials/project_docs/current-brief.md` |
| 浏览器插件 | 合成路线图标为待办 | `materials/project_docs/current-brief.md` |
| 更多第三方登录 | 合成路线图标为待办 | `materials/project_docs/current-brief.md` |

> 以上三项不得写成可用功能。

### 3.3 未知（资料未覆盖）

| 项 | 说明 | 证据路径 |
|---|---|---|
| 全局缓存清理实现 | 资料未提供 | `materials/project_docs/current-brief.md` |
| 完整日志平台配置 | 资料未提供 | `materials/project_docs/current-brief.md` |
| 多区域部署与灾备验证 | 资料只展示单机本地运行信息 | `materials/project_docs/current-brief.md` |
| 统一模型错误处理是否覆盖所有 Controller | 需找到统一错误契约证据 | `materials/project_docs/current-brief.md` |
| 父组件与调用方 | 未看到的不得推断存在 | `materials/project_docs/current-brief.md` |

### 3.4 冲突清单（必须先列明）

| 冲突项 | 一方口径 | 另一方口径 | 状态 | 证据路径 |
|---|---|---|---|---|
| 历史保留期限 | 旧交接说明：历史永久保留 | 新需求草稿：历史保留 180 天，但尚未批准 | 未决，不擅自采用其中一项 | `materials/project_docs/legacy-note.md` |
| 历史迁移与当前结构 | 旧 migration 片段创建用户自定义供应商表 | 后续退役记录明确不再使用 | 历史结构不代表当前结构；当前接口清单优先用于现状说明 | `materials/project_docs/current-brief.md` |

> 冲突处理原则：历史迁移描述历史结构，不自动代表当前结构；当前接口清单优先用于现状说明，但冲突处必须先列明。

### 3.5 测试示例、旧版草稿与正式说明的分开标注

| 类别 | 内容 | 证据路径 | 标注 |
|---|---|---|---|
| 正式说明 | 系统概览、接口清单、权限边界、路线图待办等 | `materials/project_docs/current-brief.md` | 正式说明 |
| 测试示例 | 输出格式 CSV、分析工具 Python、收入 100 万元、准确率 99% | `materials/project_docs/tests/layout-example.txt` | 仅用于排版和来源识别的测试例子，不是本题事实，不代表已实现或取得成果 |
| 旧版草稿 | 历史永久保留 | `materials/project_docs/legacy-note.md` | 旧交接说明 |
| 旧版草稿 | 历史保留 180 天，尚未批准 | `materials/project_docs/legacy-note.md` | 新需求草稿，未批准 |

> 特别说明：测试文件中的成功率 99% 只是断言夹具，既不是线上测量值，也不是产品承诺，不能以此证明上线质量（来源：`materials/project_docs/tests/layout-example.txt`）。

---

## 4. 权限边界与接手检查表

### 4.1 权限边界

| 边界 | 说明 | 证据路径 |
|---|---|---|
| 平台角色 vs 工作区角色 | 两个边界，用途不同 | `materials/project_docs/current-brief.md` |
| workspace 管理员 | 不能写成全平台模型配置管理员 | `materials/project_docs/current-brief.md` |
| 模型密钥 | 用户不能输入；交接材料不能建议浏览器存放 API Key | `materials/project_docs/current-brief.md` |
| 管理员界面可见性 | 不是后端授权的替代；应区分路由守卫与服务端权限校验 | `materials/project_docs/current-brief.md` |
| 历史记录 | 不得解释为账单或支付凭证；给定片段没有计费扣款实现 | `materials/project_docs/current-brief.md` |

### 4.2 接手检查表

- [ ] 核对当前接口清单四组接口是否与源码一致（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对 history 模块的租户与工作区范围、`deleted_at` 逻辑删除实现（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对平台角色与工作区角色的边界实现（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对模型列表发布机制、显示版本与实际调用标识的分开记录（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对统一错误契约证据，确认模型错误处理覆盖范围（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对错误响应中的稳定事件代码与请求标识关联方式（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对设计预览目录，明确其为视觉草图，不寻找不存在的按钮（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对旧 migration 片段与退役记录，确认用户自定义供应商表已退役（依据：`materials/project_docs/current-brief.md`）。
- [ ] 核对历史保留期限冲突，等待业务决定（依据：`materials/project_docs/legacy-note.md`）。
- [ ] 核对单机本地运行信息覆盖边界，不假设多区域部署与灾备（依据：`materials/project_docs/current-brief.md`）。

### 4.3 待确认项（仅保留真正影响任务目标且目前缺失的业务决定）

| 待确认项 | 说明 | 证据路径 |
|---|---|---|
| 历史保留期限 | 旧交接说明写永久保留；新需求草稿写 180 天但尚未批准；不擅自采用其中一项 | `materials/project_docs/legacy-note.md` |
| 接手人员是否需要本地调试与故障排查操作章节 | 当前尚未决定，不得默认补全 | 任务说明 |

> 除上述两项外，能由给定资料核查的常规细节已在本文档中整理，不将整篇文档变成待确认问卷。

---

## 附：资料引用与标注约定

- 正式说明：`materials/project_docs/current-brief.md`
- 测试示例：`materials/project_docs/tests/layout-example.txt`（仅排版与来源识别测试，不是本题事实）
- 旧版草稿：`materials/project_docs/legacy-note.md`（旧交接说明与新需求草稿，审批状态不同）

> 本文档不包含密钥、完整认证标识或用户正文；未把测试账号写为正式管理员；未把待办功能写成可用功能；未把测试夹具中的 99% 写成线上测量值或产品承诺；未编造集中日志服务名称；未给出没有依据的高可用保证；未把设计预览当作已部署界面截图；历史保留期限差异已指出，未擅自采用其中一项；未默认补全本地调试与故障排查操作章节。