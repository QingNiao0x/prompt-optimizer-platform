# API 后端

技术栈：Java 21、Spring Boot 3、Spring MVC、Spring Data JPA、Flyway、PostgreSQL Driver、Spring Data Redis。

当前已完成后端核心 MVP：基础工程、统一响应与错误模型、上下文感知 Plan Mode、提示词增强编排、Mock Provider、可切换的 OpenAI 兼容 Provider、语义向量检索、可选的 Map-Reduce 全文摘要，以及 Provider 配置管理基础 CRUD。

Provider 配置管理接口：

```text
GET    /api/v1/provider-configs
POST   /api/v1/provider-configs
PATCH  /api/v1/provider-configs/{id}
DELETE /api/v1/provider-configs/{id}
```

API Key 使用 AES-256-GCM 加密后写入 `provider_config.api_key_ciphertext`，接口只返回 `apiKeyLast4`，不提供明文读取。使用前必须配置加密主密钥：

```text
API_KEY_ENCRYPTION_SECRET=<由运行环境注入的加密主密钥>
```

优化历史接口：

```text
GET    /api/v1/optimization-history?page=0&size=20
GET    /api/v1/optimization-history/{id}
DELETE /api/v1/optimization-history/{id}
POST   /api/v1/optimization-history/{id}/re-optimize
```

每次 `POST /api/v1/optimizations` 成功后会自动保存一条历史记录。记录只保存脱敏后的上下文摘要，不保存上传文件的正文；重新优化使用保存的原始提示词和脱敏摘要再次调用增强流程，并生成一条新记录。

在登录鉴权实现前，接口使用固定的本地演示上下文（租户、用户、工作区），由 `V3__demo_bootstrap.sql` 和 `app.demo.*` 配置提供；接入真实用户体系后替换该上下文来源。

## 本地启动

需要 JDK 21 和 Maven 3.9+：

先在项目根目录启动 PostgreSQL 和 Redis：

```powershell
docker-compose -f infra/docker-compose.yml up -d
```

首次启动会由 Flyway 执行 `V1__init_schema.sql` 创建首版核心表。当前使用 Mock Provider 联调时，不需要 DeepSeek API Key：

```powershell
$env:MODEL_PROVIDER_MODE="mock"
```

```powershell
mvn -s ..\..\.mvn\settings.xml spring-boot:run
```

如果你已将 Maven 本地仓库配置为可写目录，也可以直接运行 `mvn spring-boot:run`。

基础健康检查：

```text
GET http://localhost:8080/api/v1/health
GET http://localhost:8080/actuator/health
```

上下文分析接口：

```text
POST http://localhost:8080/api/v1/context/analyze
POST http://localhost:8080/api/v1/context/planning
```

请求体传入用户主动选择的相对文件路径、文件内容和可选项目描述；接口不会读取用户电脑上的绝对路径。`/context/planning` 会额外返回 30 分钟有效的 `contextId/version`，供计划接口引用；计划 Provider 只接收裁剪摘要。

提示词增强接口：

```text
POST http://localhost:8080/api/v1/optimizations/plan
POST http://localhost:8080/api/v1/optimizations
```

有文件时调用顺序为 `/context/planning → /optimizations/plan → /optimizations`。短期上下文和计划优先保存在 Redis，Redis 未配置或暂时不可用时只在当前进程中降级保存；两类会话默认 TTL 都是 30 分钟。

项目默认通过 OpenAI 兼容协议调用 DeepSeek。启动前必须设置自己的 DeepSeek API Key：

```text
MODEL_API_KEY=替换为运行环境中的 DeepSeek 密钥
```

默认配置如下，也可以通过环境变量覆盖。不要把真实 API Key 提交到 Git：

```text
MODEL_PROVIDER_MODE=openai-compatible
MODEL_PROVIDER_NAME=deepseek
MODEL_ENDPOINT=https://api.deepseek.com/chat/completions
MODEL_API_KEY=替换为运行环境中的密钥
MODEL_NAME=deepseek-chat
MODEL_TEMPERATURE=0.2
MODEL_MAX_TOKENS=3000
MODEL_CONNECT_TIMEOUT=3s
MODEL_READ_TIMEOUT=60s
MODEL_JSON_RESPONSE_FORMAT_ENABLED=true
```

`MODEL_ENDPOINT` 应填写完整的 Chat Completions 请求地址。部分自定义兼容端点不接受 `response_format` 参数，此时可设置
`MODEL_JSON_RESPONSE_FORMAT_ENABLED=false`；系统提示词仍会要求返回严格 JSON。

大型文档默认使用零费用的本地规则摘要。需要让当前聊天模型对全部已索引文本执行分批 Map 和分层 Reduce 时，可以显式启用：

```text
MAP_REDUCE_SUMMARY_ENABLED=true
MAP_REDUCE_MAP_BATCH_SIZE=8
MAP_REDUCE_REDUCE_BATCH_SIZE=24
MAP_REDUCE_MAX_BATCH_CHARACTERS=48000
MAP_REDUCE_INTERMEDIATE_CHARACTERS=1200
MAP_REDUCE_FINAL_CHARACTERS=1800
MAP_REDUCE_MAX_MAP_CALLS=256
MAP_REDUCE_MAX_REDUCE_CALLS=32
```

Map-Reduce 复用 `MODEL_ENDPOINT`、`MODEL_NAME` 和 `MODEL_API_KEY`，不需要把密钥再写入配置文件。开启后会增加模型请求次数和费用；某个批次请求失败或达到调用保护上限时，系统会把该部分降级为本地规则摘要，全文索引和后续检索仍然可用。

如需在没有 API Key 的情况下进行本地页面或接口联调，可显式设置 `MODEL_PROVIDER_MODE=mock`。Mock 只生成确定性的测试结果，不会访问任何外部模型。

真实 Provider 会校验 `BACKGROUND`、`TASK`、`OUTPUT`、`CONSTRAINTS`、`ACCEPTANCE` 五个必需段落，并将上游鉴权失败、限流、超时、服务不可用和无效响应转换为稳定的平台错误码。当前不会自动重试，避免单次请求产生不可控的重复费用；后续将结合总耗时预算和幂等策略增加有限重试。

默认数据库和 Redis 配置通过环境变量覆盖：

```text
DB_URL
DB_USERNAME
DB_PASSWORD
REDIS_URL
SERVER_PORT
FLYWAY_ENABLED
```

运行完整测试：

```powershell
mvn -s ..\..\.mvn\settings.xml test
```

测试使用模拟 HTTP 服务，不会调用真实模型，也不需要 API Key。当前测试通过排除数据库和 Redis 自动配置运行；接入本地基础设施后，再增加真实连接测试。

后续实现顺序：鉴权、额度与计费 → 通用场景动态输出 → 图片/PDF 能力检测与提取。

## 仅验证核心接口的本地 Mock 模式

如果暂时只验证前后端核心接口、不验证 PostgreSQL 和 Redis，可以使用 `local-mock` 配置启动：

```powershell
mvn -s ..\..\.mvn\settings.xml spring-boot:run '-Dspring-boot.run.profiles=local-mock'
```

该配置会关闭数据库、Flyway、Redis 自动装配和历史持久化，只启用 Mock Provider；完整本地环境仍应先启动 PostgreSQL 和 Redis。

## Windows 启动排查

如果 Maven 测试和 JAR 打包都成功，但启动日志在 Tomcat 监听端口前出现下面的异常：

```text
Unable to establish loopback connection
java.net.SocketException: Invalid argument: connect
```

先确认进程是否运行在受沙箱限制的执行环境中。Java NIO 在 Windows 上创建回环管道（`Pipe.open()`）时，如果沙箱或安全软件拦截了 AF_UNIX 套接字，就会在 Tomcat 启动前报上述异常，此时项目代码、数据库、Redis 和 API Key 都正常。遇到这种情况，优先直接从 IDEA、正常 PowerShell 或不受限的终端启动。

本项目使用 JDK 21 LTS。启动前请确认 `java -version` 和 `mvn -version` 都指向 JDK 21，并在 IDEA 中将项目 SDK 和运行配置的 JRE 切换为同一个 JDK 21。

本机已验证：使用 JDK 21 在非受限环境直接运行打包后的 JAR，`local-mock` 配置可以正常启动并完成冒烟测试。

如果使用 JDK 21 后仍无法启动，再检查 Docker Desktop 是否已启动；Docker 负责 PostgreSQL 和 Redis 容器，但 `local-mock` 模式本身不依赖这两个服务。
