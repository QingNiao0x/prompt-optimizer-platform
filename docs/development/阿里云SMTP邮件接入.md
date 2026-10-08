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

- Windows 用户环境变量需被 IDEA/Cursor 启动的 Java 进程继承；设置后重启 IDE，再启动后端。不要输出密码值、粘贴到聊天或放在命令行参数中。
- Spring Boot 不自动加载仓库根目录 `.env`；`.env.example` 仅说明变量名。容器化时必须将变量或 Secret 显式注入 API 容器，不能只配置 Windows 用户变量。
- SMTP 是后端主动连接云服务，不要求服务器监听 465，也不要求新增容器端口映射或公网入站 465 规则；如果部署环境限制出站流量，再核对到 SMTP 主机 TCP/465 的出站连通性。
- 保留 `EMAIL_VERIFICATION_REQUIRE_REDIS=true`。公网使用真实 PostgreSQL 和共享 Redis；Redis 故障时不绕过验证码限流。
- `EMAIL_REGISTRATION_ENABLED` 保持开启，验证码 HMAC 密钥不可复用 SMTP 密码或短信密钥。

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
