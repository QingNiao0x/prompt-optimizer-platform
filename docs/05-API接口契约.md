# API 接口契约（Draft）

## 1. 通用约定

- 基础路径：`/api/v1`。
- 内容类型：业务请求使用 `application/json`；大型文档分片使用 `application/octet-stream`。
- 时间：ISO-8601 UTC。
- ID：UUID 字符串。
- 当前用户、租户和默认工作区从服务端认证上下文获取，不接受客户端自行指定作用域。
- 建议通过 `Idempotency-Key` 支持优化请求重试。
- 成功响应和错误响应均应包含 `requestId`。

### 当前认证约定

业务 API 必须登录；浏览器使用 HttpOnly Session Cookie。先调用 `GET /api/v1/auth/csrf`，再用 `email`、`password` 调用 `POST /api/v1/auth/login`；邮箱注册先调用 `POST /api/v1/auth/registration-code`，再向 `POST /api/v1/auth/register` 提交 `email`、`verificationCode` 和 `password`。注册成功会直接建立登录 Session。所有写请求都将 `XSRF-TOKEN` Cookie 的值通过 `X-XSRF-TOKEN` 请求头回传。`GET /api/v1/auth/me` 读取当前身份，`POST /api/v1/auth/logout` 退出。健康检查、CSRF 初始化、登录和注册接口可匿名访问，但写接口仍必须校验 CSRF。

`contextId` 和 `planId` 只能由创建它们的用户使用。跨用户引用与不存在/过期使用相同错误；不能将前端回传的 ID 视为授权。完整配置和错误约定见[最小认证与 Plan 会话所有权](./development/最小认证与Plan会话所有权.md)。

## 2. 优化接口

供应商路由、端点和密钥由平台服务端管理。已登录用户可通过 `GET /api/v1/models` 读取管理员发布的模型目录，并在 Plan 与最终增强请求中提交可选的 `modelId`（最多 160 字符）；省略时使用平台默认模型。服务端校验目录启用状态与路由，不接受客户端提交 Provider、endpoint 或 API Key。`provider` 响应元数据用于展示实际调用结果和追溯。

平台管理员通过 `GET/POST /api/v1/admin/models`、`GET /api/v1/admin/models/routes`、`PUT/DELETE /api/v1/admin/models/{id}` 维护模型。管理接口仅接受已配置路由的 `routeKey`、`upstreamModel`、`displayName`、`enabled`、`defaultModel`、`sortOrder`；更换上游模型时须新增并停用旧项，删除为逻辑删除。需要平台管理员身份，写请求仍须携带 CSRF；路由响应不含端点与密钥。

`displayName` 表示管理员维护的**模型版本名称**（去除首尾空白后 1—120 字符，例如 `DeepSeek-V4-Pro`），不从路由 ID 猜测版本。前端共用 `formatModelVersion`，将已约定的旧名称 `DeepSeek-V4-Pro-0813` 显示为 `DeepSeek-V4-Pro`；其他名称原样展示，不通用删除末尾数字。工作台、顶栏、结果、历史及管理员列表共用此规则，管理员编辑框保留原始配置值。Plan 与增强响应的 `provider.modelVersion` 仍为调用开始时的原始名称快照，默认 `""`；未配置、旧记录或实际返回路由不匹配时不伪造版本。`provider.model` 仍为调用标识，`modelId` 请求契约不变。显示短名不修改目录数据、上游调用 ID 或历史快照。

最终生成接口支持两种前端路径：默认的“直接增强”和用户主动开启的“可选上下文准备 + Plan 确认 + 最终生成”。完整字段、上下限和科研示例见[内置 Plan Mode](./12-内置Plan-Mode交互与接口.md)。

### `POST /api/v1/context/planning`

用途：当用户已经上传文件或建立本地目录索引时，先过滤并分析初步相关上下文，返回 30 分钟有效的 `contextId/version`、供计划模型使用的安全摘要，以及供工作台展示的 `contextReport`。计划模型只接收摘要，不接收该接口中的文件正文。

```json
{
  "rawPrompt": "给用户模块添加登录功能",
  "context": {
    "customDescription": "Spring Boot 用户服务",
    "files": [{"path": "pom.xml", "content": "<project>...</project>", "language": "xml"}]
  },
  "permissionPolicy": {"protectedPaths": [], "requireConfirmationFor": []}
}
```

响应关键字段为 `contextId`、`version`、`digest`、`contextReport`、`expiresAt` 和 `latencyMs`。Redis 可用时短期会话写入 Redis 并设置 30 分钟 TTL；本地联调可降级为进程内存储。

### `POST /api/v1/optimizations/plan`

用途：识别真正影响结果的业务问题并返回候选答案。计划阶段不接收项目文件正文；有文件时只引用上一步产生的安全摘要，也不保存优化历史。

```json
{
  "rawPrompt": "分析2015-2025年某地区心脑血管疾病死亡率",
  "modelId": "tokenhub:kimi-k3",
  "contextDescription": "公共卫生研究",
  "conversationHistory": [],
  "planningContext": {
    "contextId": "ea9d3453-5bd7-487b-bafb-5ef608dfd895",
    "version": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

响应中的 `questions` 最多 8 个，回答方式为 `FREE_TEXT`、`SINGLE_CHOICE` 或 `MULTIPLE_CHOICE`。响应还返回 `planId`、本次使用的 `planningContext` 和 `expiresAt`。当 `questions` 为空时，前端携带空答案和计划编号直接进入最终生成。`templateCode` 是服务端内部生成策略，工作台不向用户展示模板选择。

### `POST /api/v1/optimizations`

用途：基于原始目标、上下文和平台约束生成最终结构化提示词并保存历史。Plan 路径还会携带并校验确认答案；直接增强路径发送 `planConfirmation: null`。

```json
{
  "rawPrompt": "分析2015-2025年某地区心脑血管疾病死亡率",
  "modelId": "tokenhub:kimi-k3",
  "context": {"customDescription": "公共卫生研究", "files": []},
  "enhancement": {
    "templateCode": "RESEARCH_ANALYSIS",
    "includeConversationHistory": false,
    "includePermissionBoundaries": true,
    "includeExamples": false
  },
  "conversationHistory": [],
  "permissionPolicy": {"protectedPaths": [], "requireConfirmationFor": []},
  "planConfirmation": {
    "planId": "d53d3b67-62b2-4505-89dd-4ca88f837391",
    "planningContext": {
      "contextId": "ea9d3453-5bd7-487b-bafb-5ef608dfd895",
      "version": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "answers": [{
      "questionId": "research-region",
      "question": "这项研究具体覆盖哪个地区？",
      "answer": "广东省"
    }]
  }
}
```

服务端校验计划编号、需求指纹、上下文版本、所选模型和完整答案集合，并以服务端保存的问题文本为准。确认答案会加入第二次上下文检索查询；文件或查询变化时重新分析，完全一致时复用首次快照。

响应包含 `optimizedPrompt`、`sections`、`contextReport`、`ambiguities`、`appliedConstraints`、`templateCode`、`provider` 和 `latencyMs`。`sections` 至少包含 `BACKGROUND`、`TASK`、`OUTPUT`、`CONSTRAINTS`；已明确回答且没有新冲突的问题不再重复提示。未决回答、二次检索的新冲突或新增业务条件仍保留在 `ambiguities`，不能因完成过 Plan 就一律清空。

Plan 最终组装先登记已核验的新冲突和绑定问题的未决状态，再合并模型提醒；兼容旧 Provider 的纯文本输出。先去重再应用最多 8 条的展示限额，仍超限时通过 `warnings` 明确告知未展示数量。对外 `ambiguities` 仍为字符串数组，与 `CLARIFICATIONS` 使用同一列表；没有新增浏览器必填字段。

Provider 内部可选的 `ambiguityReferences` 校验失败时，仅弃用无效关联并记录安全诊断，不因辅助字段错误重试或拒绝有效结果。直接增强与 Plan 增强都保留已校验的正文和实际未决问题；必需段落、提醒正文、敏感内容及客户端输入校验不降级。字段边界和复合提醒规则见 [内置 Plan Mode](./12-内置Plan-Mode交互与接口.md)。

工作台直接增强时明确发送 `planConfirmation: null`，并把 `enhancement.templateCode` 重置为 `AUTO`，由服务端根据本次输入重新推断策略；此时服务端保留模糊点检测，响应可能包含 `ambiguities`。其他兼容客户端也可以省略 `planConfirmation`。平台默认权限红线不能通过 `includePermissionBoundaries=false` 关闭，用户规则只能追加。

## 3. 历史接口

> 实现状态：分页、详情、删除和重新优化已实现；最终优化自动保存，计划接口不保存。

列表和详情新增 `modelVersion` 字符串，来自该条记录 `result_metadata.modelVersion` 的调用时快照；原 `modelName` 调用标识保持兼容。旧历史缺少此键时返回 `""`，页面显示“版本未记录”，不会用当前管理员目录名称倒填。无需新增数据库字段。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/optimization-history?page=&size=` | 分页查询历史，`size` 限制为 1—50 |
| `GET` | `/api/v1/optimization-history/{id}` | 查询详情 |
| `DELETE` | `/api/v1/optimization-history/{id}` | 逻辑删除当前工作区历史；后续列表、详情和再次优化不再返回该记录 |
| `POST` | `/api/v1/optimization-history/{id}/re-optimize` | 恢复原始输入和确认答案，生成一条新记录 |

## 4. 上下文上传接口（可选分步模式）

### `POST /api/v1/context/analyze`

用途：分析用户主动选择并提交的文件内容，返回短时 Context Snapshot。该接口不接收或读取用户电脑上的绝对路径。

请求示例：

```json
{
  "customDescription": "这是一个 Spring Boot + Vue 项目。",
  "files": [
    {
      "path": "backend/pom.xml",
      "content": "<project>...</project>",
      "language": "xml"
    }
  ]
}
```

响应中的 `technologyStack`、`dependencies`、`directoryTree`、`fileSnippets`、`warnings` 和 `redactions` 均为分析结果；原始内容不默认持久化。`technologyStack` 的每一项包含识别名称、来源文件和置信度，`fileSnippets` 的每一项包含路径、语言、短时内容片段、独立内容摘要和截断状态。图片只返回元数据摘要，并通过 `warnings` 明确提示尚未进行 OCR 或视觉识别。

响应还包含：

- `analysisStatus`：`EMPTY`、`COMPLETE`、`PARTIAL` 或 `FAILED`。
- `fileCoverage`：每个文件的原始大小、提取字符数、索引片段数、本次选取片段数和不完整原因。

### 大型文档分片上传与临时全文索引

Office、PDF、图片以及超过 1 MB 的普通文档不再编码成超大 Base64 JSON。前端先创建上传任务，再按服务端返回的 1 MiB 分片大小上传原始字节：

| 方法 | 路径 | 内容类型 | 用途 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/context/documents` | `application/json` | 创建上传任务并返回 `documentId`、分片大小和状态 |
| `PUT` | `/api/v1/context/documents/{documentId}/chunks/{chunkIndex}` | `application/octet-stream` | 上传固定编号分片；相同编号允许安全重试 |
| `POST` | `/api/v1/context/documents/{documentId}/complete` | `application/json` | 校验分片完整性并启动异步解析 |
| `GET` | `/api/v1/context/documents/{documentId}` | `application/json` | 查询上传、排队、解析、索引和摘要进度 |
| `DELETE` | `/api/v1/context/documents/{documentId}` | `application/json` | 取消处理并提前清除临时文件和索引 |

创建任务示例：

```json
{
  "path": "docs/大型研究报告.docx",
  "language": "docx",
  "sizeBytes": 31457280
}
```

解析完成后，`POST /api/v1/context/analyze` 和 `POST /api/v1/optimizations` 只携带轻量引用：

```json
{
  "path": "docs/大型研究报告.docx",
  "content": "",
  "language": "docx",
  "documentId": "4f83b5e8-...",
  "sizeBytes": 31457280
}
```

后端会为“分析上下文”选择全文均匀分布的代表片段，为“一键增强”按照原始提示词检索相关片段；当提示词没有字面命中或命中不足时，剩余名额也会从全文均匀补位，避免泛化任务偏向文档开头。可选的 Map-Reduce 摘要会在 `SUMMARIZING` 阶段按批次覆盖全部已索引文本，并通过 `progressPercent=95..99` 报告进度；模型失败或达到调用保护上限时返回可读警告并降级为本地规则摘要，不会使全文索引失效。

Map-Reduce 默认关闭，启用变量为 `MAP_REDUCE_SUMMARY_ENABLED=true`。它复用当前 `MODEL_*` 聊天模型配置，不要求在配置文件中填写第二份 API Key。启用远程模型意味着文档正文会分批离开本机，产品界面必须明确说明传输目标和费用；未启用时保持原有规则摘要行为。

单个文档当前上限为 50 MiB，提取内容安全上限为 100,000,000 字符，临时索引 TTL 为 2 小时；源文件在解析结束后立即删除，索引只保存在服务端临时目录，不写入 PostgreSQL 或优化历史。

当前 `documentId` 是进程内短时引用，不是长期项目文件 ID。用户清空文件、关闭页面或任务到期时会清理；生产环境增加用户登录后，还必须把该 ID 与用户、租户和工作区绑定。

## 6. 错误模型

```json
{
  "requestId": "req_01J...",
  "error": {
    "code": "CONTEXT_TOO_LARGE",
    "message": "项目上下文超过当前模型预算，请减少文件或使用摘要模式。",
    "retryable": false,
    "details": {
      "maxBytes": 1048576,
      "receivedBytes": 2678120
    }
  }
}
```

建议错误码：`INVALID_ARGUMENT`、`UNAUTHORIZED`、`FORBIDDEN`、`RATE_LIMITED`、`QUOTA_EXCEEDED`、`CONTEXT_TOO_LARGE`、`UNSUPPORTED_FILE`、`PROVIDER_TIMEOUT`、`PROVIDER_AUTH_FAILED`、`PROVIDER_RATE_LIMITED`、`PROVIDER_REQUEST_REJECTED`、`PROVIDER_UNAVAILABLE`、`RESULT_INVALID`、`INTERNAL_ERROR`。

Provider 错误不会透传上游响应正文、API Key 或用户源码。`PROVIDER_RATE_LIMITED`、`PROVIDER_TIMEOUT` 和临时不可用错误可标记为可重试；鉴权失败、请求被拒绝和结构化结果无效默认不可重试。

平台并发控制错误与上游 Provider 错误分开返回，均使用现有统一错误结构，`retryable=true`、`details.retryAfterSeconds=1`，响应头包含 `Retry-After: 1`。该提示是建议等待时间，不承诺一秒后一定有空闲名额。

| HTTP 状态 | 错误码 | 含义 |
| --- | --- | --- |
| 429 | `USER_MODEL_CONCURRENCY_LIMIT` | 同一服务端认证账号已有三个模型相关请求执行中。 |
| 503 | `MODEL_CONCURRENCY_LIMIT` | 平台已有一百个模型 HTTP 调用执行中，本次未发送上游请求。 |
| 503 | `MODEL_CONCURRENCY_UNAVAILABLE` | 共享计数不可用，本次拒绝新增调用。 |

账号限制覆盖 `POST /api/v1/optimizations`、`POST /api/v1/optimizations/plan`、`POST /api/v1/context/analyze`、`POST /api/v1/context/planning` 和 `POST /api/v1/optimization-history/{id}/re-optimize`；登录、历史读取及健康检查不占用账号名额。100/3 为默认值，部署可统一调整。
