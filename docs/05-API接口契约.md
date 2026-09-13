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

### `POST /api/v1/optimizations`

用途：基于原始提示词、项目上下文和用户偏好创建一次优化请求。

请求示例：

```json
{
  "workspaceId": "8b1f2c50-2b7f-4f20-94b0-1f2e6d1d9a10",
  "rawPrompt": "帮我给这个项目增加登录功能",
  "context": {
    "customDescription": "这是一个 Spring Boot 微服务项目，使用 PostgreSQL 和 Redis。",
    "files": [
      {
        "path": "pom.xml",
        "content": "<project>...</project>",
        "language": "xml"
      }
    ],
    "projectSummary": {
      "rootName": "demo-service",
    "technologyStack": ["Java 21", "Spring Boot 3", "PostgreSQL", "Redis"],
      "importantDirectories": ["src/main", "src/test"]
    }
  },
  "preferences": {
    "style": "readable",
    "includeExamples": true,
    "outputLanguage": "zh-CN"
  },
  "options": {
    "providerConfigId": "6ec5bd9a-2c4d-4d28-b1f5-58ab3a4f7a91",
    "saveHistory": true
  }
}
```

响应示例：

```json
{
  "requestId": "req_01J...",
  "data": {
    "recordId": "c99b19d0-7a1d-4b61-ae36-c37d59e0f2f1",
    "optimizedPrompt": "你是一名资深 Java 后端工程师……",
    "sections": [
      "任务目标",
      "项目背景",
      "输入输出",
      "约束条件",
      "实施步骤",
      "验收标准"
    ],
    "contextReport": {
      "detectedStack": ["Java", "Spring Boot", "PostgreSQL", "Redis"],
      "filesAnalyzed": 2,
      "warnings": ["部分源码因上下文预算被截断"]
    },
    "usage": {
      "inputTokens": 1200,
      "outputTokens": 850,
      "latencyMs": 3200
    }
  }
}
```

### 增强请求的关键字段

优化请求应支持以下扩展字段：

```json
{
  "enhancement": {
    "oneClick": true,
    "templateCode": "FEATURE_DEVELOPMENT",
    "includeConversationHistory": true,
    "includePermissionBoundaries": true,
    "includeExamples": true,
    "outputSections": ["BACKGROUND", "TASK", "OUTPUT", "CONSTRAINTS", "ACCEPTANCE"]
  },
  "conversationHistory": [
    {
      "role": "user",
      "content": "当前用户模块已经支持邮箱登录"
    }
  ],
  "permissionPolicy": {
    "protectedPaths": [".env", "**/*.pem"],
    "requireConfirmationFor": ["DELETE_FILE", "DATABASE_MIGRATION", "PRODUCTION_DEPLOY"]
  }
}
```

这些字段不要求用户全部填写：模板、结构和通用安全约束可以由系统默认提供；用户只需输入原始提示词，并可选补充项目背景、会话历史和自定义规则。

## 3. 历史接口

> 实现状态：历史接口尚未实现，先完成 Provider 配置管理后再进入本模块。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/optimization-records?workspaceId=&page=&size=` | 分页查询历史 |
| `GET` | `/api/v1/optimization-records/{id}` | 查询详情 |
| `DELETE` | `/api/v1/optimization-records/{id}` | 删除用户可见历史 |
| `POST` | `/api/v1/optimization-records/{id}/reoptimize` | 用原始输入重新优化 |
| `GET` | `/api/v1/optimization-records/{id}/revisions` | 查看增强结果版本 |
| `POST` | `/api/v1/optimization-records/{id}/revisions` | 保存用户编辑后的版本 |

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

后端会为“分析上下文”选择全文均匀分布的代表片段，为“一键增强”按照原始提示词检索相关片段；当提示词没有字面命中或命中不足时，剩余名额也会从全文均匀补位，避免泛化任务偏向文档开头。单个文档当前上限为 50 MiB，提取内容安全上限为 100,000,000 字符，临时索引 TTL 为 2 小时；源文件在解析结束后立即删除，索引只保存在服务端临时目录，不写入 PostgreSQL 或优化历史。

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
