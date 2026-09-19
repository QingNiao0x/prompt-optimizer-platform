# API 接口契约（Draft）

## 1. 通用约定

- 基础路径：`/api/v1`。
- 内容类型：业务请求使用 `application/json`；大型文档分片使用 `application/octet-stream`。
- 时间：ISO-8601 UTC。
- ID：UUID 字符串。
- 所有需要工作区的数据都携带 `workspaceId`，不从客户端传入任意 `tenantId`。
- 建议通过 `Idempotency-Key` 支持优化请求重试。
- 成功响应和错误响应均应包含 `requestId`。

## 2. 优化接口

优化采用“可选上下文准备 + 计划确认 + 最终生成”流程。完整字段、上下限和科研示例见[内置 Plan Mode](./12-内置Plan-Mode交互与接口.md)。

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

用途：在用户完成计划确认后，基于原始目标、上下文、确认答案和平台约束生成最终结构化提示词并保存历史。

```json
{
  "rawPrompt": "分析2015-2025年某地区心脑血管疾病死亡率",
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

服务端校验计划编号、需求指纹、上下文版本和完整答案集合，并以服务端保存的问题文本为准。确认答案会加入第二次上下文检索查询；文件或查询变化时重新分析，完全一致时复用首次快照。

响应包含 `optimizedPrompt`、`sections`、`contextReport`、`ambiguities`、`appliedConstraints`、`templateCode`、`provider` 和 `latencyMs`。`sections` 至少包含 `BACKGROUND`、`TASK`、`OUTPUT`、`CONSTRAINTS`；完成计划确认后 `ambiguities` 为空，不再要求用户修改待确认项。

兼容旧客户端时可以省略 `planConfirmation`，此时服务端仍保留原有模糊点检测。平台默认权限红线不能通过 `includePermissionBoundaries=false` 关闭，用户规则只能追加。

## 3. 历史接口

> 实现状态：分页、详情、删除和重新优化已实现；最终优化自动保存，计划接口不保存。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/optimization-history?page=&size=` | 分页查询历史，`size` 限制为 1—50 |
| `GET` | `/api/v1/optimization-history/{id}` | 查询详情 |
| `DELETE` | `/api/v1/optimization-history/{id}` | 删除当前工作区历史 |
| `POST` | `/api/v1/optimization-history/{id}/re-optimize` | 恢复原始输入和确认答案，生成一条新记录 |

## 4. Provider 配置接口

> 实现状态：增删改查已实现；`/test` 最小探测接口待实现。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/provider-configs` | 查询配置摘要，不返回明文 Key |
| `POST` | `/api/v1/provider-configs` | 新增配置 |
| `PATCH` | `/api/v1/provider-configs/{id}` | 更新模型、端点或参数 |
| `DELETE` | `/api/v1/provider-configs/{id}` | 删除配置和密文 |
| `POST` | `/api/v1/provider-configs/{id}/test` | 发送最小探测请求验证配置 |

配置请求的 API Key 只允许写入，不允许读取：

```json
{
  "providerType": "OPENAI_COMPATIBLE",
  "modelName": "gpt-4o-mini",
  "endpointUrl": "https://api.example.com/v1",
  "apiKey": "sk-...",
  "parameters": {
    "temperature": 0.2,
    "maxTokens": 3000
  }
}
```

## 5. 上下文上传接口（可选分步模式）

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
