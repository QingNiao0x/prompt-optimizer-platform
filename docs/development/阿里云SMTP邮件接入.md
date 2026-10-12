# 阿里云 SMTP 邮件接入

## 1. 已实现的范围

复用原有 `EmailRegistrationServiceImpl`、验证码存储和注册接口，通过 Spring Boot 的 `JavaMailSender` 投递注册验证码。新增 SMTP 适配器使用 UTF-8、纯文本正文和产品署名，不使用阿里云控制台模板编号，也不依赖短信 PNVS 的 AccessKey。

项目内 `VerificationEmailTemplate` 保存三份文案：

| 模板 | 邮件标题 | 当前状态 |
| --- | --- | --- |
| 邮箱注册 | `【PromptOptimizer】邮箱注册验证码` | 注册发码时使用 |
| 登录验证码 | `【PromptOptimizer】｜登录验证码` | 仅预留文案，未开放邮箱验证码登录 |
| 邮箱绑定 | `【PromptOptimizer】｜邮箱绑定验证` | 仅预留文案，未开放邮箱绑定接口 |

绑定邮件标题不再误用“登录验证码”。SMTP 会将项目模板中的 `{Code}`、`{Minutes}` 替换成服务端生成的验证码及有效时长；修改云控制台模板不会修改本项目的 SMTP 正文。

本轮不增加邮箱绑定、验证码登录、改密、找回密码或账号合并功能，不改变短信流程、身份唯一约束、工作区和优化历史。

## 2. 推荐配置：阿里云杭州 465/SSL

先确保发信域名、发信地址已验证，发信地址已设置 SMTP 密码。发信账号与邮件 From 地址应为同一个已验证地址。

在**后端进程的环境变量或密钥管理服务**配置以下项目，不将真实值写进仓库：

```text
SPRING_PROFILES_ACTIVE=aliyun-smtp
SMTP_USERNAME=<已验证的发信地址>
ALIBABA_SMTP_PASSWORD=<该发信地址的 SMTP 密码>
EMAIL_VERIFICATION_SECRET=<独立生成的至少32字节随机密钥>
```

若已有其他必要 profile，将 `aliyun-smtp` 加到现有列表，不覆盖原配置；不要添加 `local-sql-debug` 或 `local-mock`。注册邮件默认启用 `smtp` 投递，原来显式设置的 `EMAIL_DELIVERY_MODE=disabled` 或 `log` 仍会覆盖 profile 默认值，需改为 `smtp`。不要用 `log` 代替真实投递。

`EMAIL_FROM_ADDRESS` 默认复用 `SMTP_USERNAME`；如设置过此变量，须核对它与发信账号一致。`EMAIL_FROM_NAME` 默认 `PromptOptimizer`，可改为公开产品名称。

`SMTP_USERNAME` 是阿里云邮件推送控制台创建并验证的完整发信地址，不是收件地址、阿里云登录账号或 AccessKey。启用 `aliyun-smtp` 后，认证账号按 `SMTP_USERNAME` → `EMAIL_FROM_ADDRESS` → 空值解析；后一项用于兼容已有部署只配置发信地址的情况。只有前一变量未定义时才回退，显式空值仍需修正。通用 SMTP 配置继续要求显式提供 `SMTP_USERNAME`，不改变其他服务商的认证方式。

`aliyun-smtp` profile 自带下列配置，不需要额外设置主机、端口和加密模式：

| 配置 | 默认值 |
| --- | --- |
| SMTP 主机 | `smtpdm.aliyun.com` |
| 端口 | `465` |
| `mail.smtp.ssl.enable` | `true` |
| `mail.smtp.ssl.checkserveridentity` | `true` |
| `mail.smtp.starttls.enable` / `required` | `false` / `false` |
| 身份认证 | `true` |
| 编码 | `UTF-8` |
| 连接超时 | 5 秒 |
| 读取／写入超时 | 各 10 秒 |

主机和端口可用 `SMTP_HOST`、`SMTP_PORT` 覆盖。若环境里保留了旧的 `SMTP_PORT=587`，先移除或改为 `465`；此 profile 固定采用隐式 SSL，不能把端口单独改成非 SSL 端口。其他阿里云区域需使用该区域的 SMTP 主机。[官方 SMTP 地址与端口](https://help.aliyun.com/zh/direct-mail/smtp-endpoints)

不采用 profile 时，也可直接设置 `EMAIL_DELIVERY_MODE=smtp`、`SMTP_HOST=smtpdm.aliyun.com`、`SMTP_PORT=465`、`SMTP_SSL_ENABLED=true`、`SMTP_STARTTLS=false`，其余配置同上。

密码读取顺序为 `ALIBABA_SMTP_PASSWORD` → `SMTP_PASSWORD` → 空值。前一变量**未定义**时才回退；定义为空不会自动改用另一密码。SMTP 密码不等于阿里云账号密码或短信 AccessKey。

通用配置仍默认端口 `587`、`SMTP_SSL_ENABLED=false`、`SMTP_STARTTLS=true`；启用 STARTTLS 时要求服务器确实支持 TLS，不静默降级明文认证。现有服务商使用其他端口或加密方式时，按其官方配置调整。

## 3. 本地与部署注意事项

- IDEA 直接运行 `PromptOptimizerApplication` 时，运行配置的环境变量也会覆盖 YAML。切换真实邮件时，须将旧的 `EMAIL_DELIVERY_MODE=log` 改为 `smtp`，并启用 `SPRING_PROFILES_ACTIVE=aliyun-smtp`；不要只修改配置文件的默认值。本机运行配置位于被 Git 忽略的 `services/api/.idea/workspace.xml`，不会作为部署配置提交。
- 杭州站点使用 `SMTP_HOST=smtpdm.aliyun.com`、`SMTP_PORT=465`、`SMTP_SSL_ENABLED=true`、`SMTP_STARTTLS=false`。这些公开连接参数可以放入本地运行配置；`SMTP_USERNAME`、`ALIBABA_SMTP_PASSWORD` 和已有 `EMAIL_VERIFICATION_SECRET` 由后端启动环境安全注入，不在共享配置中保存真实值。验证码密钥无需因切换邮件服务而轮换。
- 发送器初始化后输出 `event=email.delivery.configured mode=SMTP sender=SmtpVerificationEmailSender`。这只表示 SMTP 模式已选中，不代表已完成认证、实际投递或收件验收；本地 `log` 模式返回的 200 也不表示邮件已发送。发信账号未注入时，仍会返回投递不可用错误，不能回退成模拟成功。
- Windows 用户环境变量需被 IDEA/Cursor 启动的 Java 进程继承；设置后重启 IDE，再启动后端。不要输出密码值、粘贴到聊天或放在命令行参数中。
- Spring Boot 不自动加载仓库根目录 `.env`；`.env.example` 仅说明变量名。容器化时必须将变量或 Secret 显式注入 API 容器，不能只配置 Windows 用户变量。
- SMTP 是后端主动连接云服务，不要求服务器监听 465，也不要求新增容器端口映射或公网入站 465 规则；如果部署环境限制出站流量，再核对到 SMTP 主机 TCP/465 的出站连通性。
- 保留 `EMAIL_VERIFICATION_REQUIRE_REDIS=true`。公网使用真实 PostgreSQL 和共享 Redis；Redis 故障时不绕过验证码限流。
- `EMAIL_REGISTRATION_ENABLED` 保持开启，验证码 HMAC 密钥不可复用 SMTP 密码或短信密钥。

### 3.1 本地与服务器沿用同一套环境变量

| 环境 | 配置位置 | 生效方式 |
| --- | --- | --- |
| Windows 本地 | 电脑的用户环境变量，或仅保存在本机的 IDE 运行环境 | 配置 `SMTP_USERNAME` 为已验证发信地址；重启 Cursor/IDEA，再启动后端以继承新变量。 |
| 服务器容器 | 部署目录中受保护的外部环境文件或 Secret，由 Compose 的 `env_file`/`environment` 注入 API 容器 | 继续沿用既有 `aliyun-smtp`、`EMAIL_DELIVERY_MODE=smtp`、发信账号、SMTP 密码与验证码密钥；改动容器环境后需要重新创建 API 容器。 |

两个环境使用相同变量名，但值由各自运行环境提供。Windows 用户变量不会自动进入服务器容器；已部署环境若只有 `EMAIL_FROM_ADDRESS`，新的阿里云 profile 可复用该值进行认证，无需为此修改源代码或 YAML。若两项都未定义，仍需在运行环境补齐发信地址，不能推断或伪造账号。

发布时只更新应用镜像或 JAR，保留服务器现有的外部环境文件和 Secret，不用本地 IDE 配置覆盖服务器配置，不将真实值打包进仓库或发布物。环境变量名称、优先级和 profile 保持兼容，后续常规部署无需反复修改项目配置文件；已有验证码密钥与 SMTP 密码不因应用更新而轮换。

### 3.2 本地旧版 Redis 发码兼容性

本机 Redis 3.2.100 不支持一次 `HSET` 写入多组字段。原邮箱发码 Lua 因此抛出 `RedisCommandExecutionException`，存储层按故障拒绝规则返回 `503`，邮件发送器尚未执行；这不是 SMTP 密码或邮件模板错误。

发码脚本现改用三次单字段 `HSET`，仍在同一个 Lua 中完成挑战写入、冷却期和小时计数更新，保留原 Redis 键、验证码摘要、限流阈值及过期时间。无需删除原验证码数据、修改 SMTP 配置或关闭 `EMAIL_VERIFICATION_REQUIRE_REDIS`。更新后须重启后端；如果使用已打包的 JAR 或容器，需要重新构建并更新对应发布物。

旧版兼容仅用于已有本地环境；生产部署继续使用项目要求的 Redis 7，不以本地兼容测试替代生产版本验收。

### 3.3 本地 Profile、Bean 注入与 401/503 排查

`EmailRegistrationServiceImpl.verificationStore` 是必需的构造器参数，Spring 注入 `HybridEmailVerificationStore` 后赋给 `final` 字段；无需手动创建或改为可选注入。如果存储 Bean 本身缺失，完整模式会在启动时失败，不会正常启动后仅在发码请求返回 503。存储内部的 `StringRedisTemplate` 才是可选依赖：允许开发模式不装配 Redis，但不等于公网 SMTP 可以绕过 Redis。

| 模式 | 邮箱注册服务及存储行为 |
| --- | --- |
| `local-mock` | 使用固定测试身份，不创建新账户、不发送邮件。注册协议入口明确返回 `503 REGISTRATION_SERVICE_UNAVAILABLE` 与 Mock 模式提示，仍要求 CSRF；不再让缺失 Controller 被误报成其他服务或认证错误。 |
| 完整开发环境 + `MODEL_PROVIDER_MODE=mock` + `aliyun-smtp` | 模拟的仅是提示词模型；注册仍走真实邮箱服务、数据库与 Redis。可用于测试真实邮件，不能追加 `local-mock` 或 SQL 参数日志 profile。 |
| 完整开发环境 + 显式 `EMAIL_DELIVERY_MODE=log`、`EMAIL_VERIFICATION_REQUIRE_REDIS=false` | 验证码生命周期固定在内存，日志投递只适合明确授权的本地开发，不代表邮件已投递。需要保留 `EMAIL_VERIFICATION_SECRET`，不使用内置或共享密钥。 |
| 公网 / 生产 SMTP | 沿用现有 `aliyun-smtp`、SMTP 凭据和 `EMAIL_VERIFICATION_REQUIRE_REDIS=true`。Redis 客户端缺失、连接失败或 Lua 失败均拒绝请求，不回退内存。 |

**按响应码区分原因，不将所有 503 归为 SMTP 密码错误：**

- `401 AUTHENTICATION_REQUIRED`：当前用户接口 `/api/v1/auth/me` 在未登录时的正常响应；正确注册发码 URL 已允许匿名访问。需要核对失败请求本身的 URL、方法和错误码，不能凭相邻的 401 日志认定发码服务未授权。
- 注册发码缺少 CSRF Cookie/Header 返回 `403 ACCESS_DENIED`；不能通过关闭 CSRF、开放所有路径或开放 `/error` 修复。
- `503 REGISTRATION_SERVICE_UNAVAILABLE`：注册关闭、验证码 HMAC 密钥不足 32 字节、强制 Redis 时客户端/连接/脚本失败，或显式 `local-mock` 不支持注册。
- `503 EMAIL_DELIVERY_UNAVAILABLE`：投递模式为 `disabled`、SMTP 客户端/From/认证配置缺失，或邮件构建、认证、连接、投递失败。邮件失败后 Redis 撤销再失败时，保留原投递错误并单独记录清理失败；不伪造成功，也不取消原有限流状态。

初始化日志 `event=email.verification.store.configured` 仅输出存储类型、`requireRedis` 和 `redisClientPresent`；客户端存在不证明 Redis 可连接。`event=email.delivery.configured` 输出 `SMTP` / `LOG` / `DISABLED`，不含地址或密钥。

请求缺少配置或服务依赖时记录以下固定原因码；SMTP 配置检查在网络发送前完成，接口保持原有统一错误，不公开部署细节：

| 日志原因码 | 检查项目 |
| --- | --- |
| `SMTP_CLIENT_MISSING` | `JavaMailSender` 是否装配，邮件自动配置是否被排除 |
| `FROM_ADDRESS_MISSING` | 后端进程实际读取的 `EMAIL_FROM_ADDRESS`，及既有 `SMTP_USERNAME` 回退 |
| `SMTP_HOST_MISSING` | 实际生效的 SMTP 主机及 `aliyun-smtp` Profile |
| `SMTP_USERNAME_MISSING` | Java 进程是否继承既有 `SMTP_USERNAME` / 兼容回退 |
| `SMTP_PASSWORD_MISSING` | Java 进程是否继承 `ALIBABA_SMTP_PASSWORD` / 既有 `SMTP_PASSWORD` |
| `DELIVERY_DISABLED` | 运行环境是否用 `EMAIL_DELIVERY_MODE=disabled` 覆盖 Profile |
| `REDIS_CLIENT_MISSING` | Redis 自动配置是否被排除，是否启用了 `local-mock` |

Windows 用户变量存在不等于已经运行的 IDEA 或 Java 进程已继承；修改用户变量后需完全退出再打开 IDEA，随后启动后端。本轮不重命名环境变量、不修改生产 YAML/Compose、不从 Windows 注册表在业务代码中绕过配置优先级读取凭据。既有本机启动配置和服务器外部 Secret 均保留。

本轮复现、修改和验证边界见[邮箱注册本地环境诊断与修复](../testing/邮箱注册本地环境诊断与修复-2026-10-12.md)：81 项最终回归及另 8 项隔离 Redis 实测通过；本机真实 SMTP 原始 503 的具体分支仍需重启后查看安全原因码，不将模拟邮件成功算作真实投递成功。

## 4. 隐私与失败行为

真实 SMTP 模式在初始化时检查并拒绝 `local-sql-debug`、`local-mock`、P6Spy、MyBatis 标准输出参数日志以及身份 Mapper、邮件客户端、根 Logger 的 DEBUG/TRACE。它不会擅自关闭全局日志，而是要求操作者明确退出会打印身份信息的诊断模式。

`mail.debug`、`mail.debug.auth` 均关闭；自定义邮件客户端若开启协议调试，同样拒绝发送。没有关闭证书或主机名校验，不配置 `ssl.trust=*`。

SMTP 失败只记录 `event=email.delivery.failed`、用途与异常类型，沿用请求日志的 requestId；不记录密码、收件地址、验证码、MimeMessage 或供应商原始错误正文。接口仍返回既有 `503` 投递不可用错误。

发送器不自动重试 SMTP，避免超时后重复发信；SMTP 接受邮件也不保证已进入收件箱，需结合实际收件和云端投递记录验收。验证码仍按原有 60 秒重发、5 分钟有效、最多 5 次错误、邮箱每小时 5 次及 IP 每小时 20 次的默认规则处理。

原有邮箱验证码消费与数据库提交分离的事务边界未在本轮重构；不能将 SMTP 适配器测试当作账号合并或验证码并发一致性的完成证据。

## 5. 验证方式与边界

自动测试使用模拟 `JavaMailSender`、虚拟邮箱与验证码，不连接阿里云、不发送真实邮件：

```powershell
# services/api
mvn.cmd '-Dtest=VerificationEmailTemplateTest,SmtpVerificationEmailSenderTest,RegistrationConfigurationTest,EmailRegistrationServiceTest,HybridEmailVerificationStoreTest,InMemoryEmailVerificationStoreTest,RegistrationControllerTest' test
```

覆盖模板标题和变量、UTF-8 署名、SMTP 失败脱敏、缺失配置、密码变量优先级、465/SSL 与通用 STARTTLS 配置、隐私调试拒绝，以及既有注册规则回归。

真实验收另行确认测试收件地址和发送次数，在页面申请验证码并由用户查看邮箱；不要把真实验证码或 SMTP 密码发到聊天中。云控制台发送测试成功，不等于本地后端的 SMTP 身份认证已验收。

2026-10-08 实施验证：上面的 60 项定向测试全部通过（47 项新增邮件检查、13 项既有邮箱注册回归），包含 Spring Boot 实际加载 `aliyun-smtp` 的检查；另外 33 项身份、管理员权限、登录保护、PNVS 和 MockMvc 登录回归通过。未发送真实邮件或短信，没有新增或回改 Flyway 迁移。

验证边界：额外的 8 项 `AuthenticationIntegrationTest` 使用模拟认证身份，但现有 `local-mock` profile 未排除 DataSource/Flyway，运行时连接了本地 PostgreSQL 并校验已有 13 个迁移，日志显示无需新迁移。不能将其描述为完全离线数据库测试；后续执行需使用独立测试库或明确基础设施隔离。本轮未重构该共享 profile，也未完成全仓库隐私审计或真实 SMTP 投递验收。

### 5.1 邮箱验证码真实 Redis 回归

`HybridEmailVerificationStoreRedisTest` 执行真实存储类的 Lua，并通过实际 Controller 和 Service 检查注册发码接口。邮件发送、身份查询、账户创建和认证均使用模拟对象，不加载真实 SMTP/PNVS 客户端或数据库。测试用随机合成指纹隔离数据，不执行 `FLUSHDB` 或 `FLUSHALL`；挑战和计数遵守原有 TTL。

先准备只绑定 `127.0.0.1:56409`、数据库编号为 `0` 的独立测试 Redis，不能将现有业务端口 6379 改为测试端点。只有以下变量精确匹配时才运行该类；普通 Maven `package` 未配置此变量时跳过，不自动连接开发实例。

```powershell
# 进入后端目录，在本机启动隔离 Redis 后显式启用真实 Lua 测试
Set-Location services/api
$env:EMAIL_REDIS_TEST_URL = 'redis://127.0.0.1:56409/0'
try {
    # 测试发码、TTL、消费、错误次数、小时限额、撤销和并发，不发送邮件
    mvn.cmd '-Dtest=HybridEmailVerificationStoreRedisTest,HybridEmailVerificationStoreTest' test
} finally {
    # 避免后续普通打包意外继承真实 Redis 测试开关
    Remove-Item Env:EMAIL_REDIS_TEST_URL -ErrorAction SilentlyContinue
}
```

测试完成后，仅关闭自行创建的隔离实例；保留原有 Redis 服务及其数据。上述真实 Redis 测试不覆盖安全过滤器；注册 CSRF 继续由 `RegistrationControllerTest` 单独回归。

2026-10-11 兼容修复验证：隔离 Redis 3.2.100 上，修复前实际注册验证码接口复现 `503` 与同类 Redis 异常，修复后返回 `200`，立即重复发码返回 `429`。8 项真实 Redis 测试及 62 项邮箱/SMTP/注册回归全部通过，共 70 项、零失败、零错误、零跳过；包含缺失或异常 Redis 必须拒绝请求的检查。未发送真实邮件或短信，未连接业务数据库或改动现有 6379 实例。Redis 7 实测及真实 SMTP 收件验收仍需另行完成。

另有 9 项身份安全和管理员权限离线回归通过。移除专用测试变量后复跑，8 项真实 Redis 用例按预期跳过，12 项单元测试通过；跳过用例不计入通过数量。隔离 Redis 已在验收后关闭，现有业务 Redis 保持不变。
