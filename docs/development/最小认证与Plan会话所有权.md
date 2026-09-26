# 最小认证与 Plan 会话所有权

当前已实现邮箱验证码注册、邮箱或用户名密码登录、退出、当前用户读取，以及 `contextId` / `planId` 的服务端所有权绑定。登录标识已从账户资料中拆分为通用 `user_identity` 模型。手机号验证码、微信 OAuth、找回密码、团队邀请和角色管理后台仍未实现。

临时上传文档也按 `CurrentActor.userId` 绑定所有者：创建时记录用户，分片写入、完成、状态、删除和上下文检索均校验同一用户。跨用户访问统一表现为资源不存在，避免根据文档 ID 探测其他用户资料；后台过期清理不使用请求身份。

## 1. 为什么目前不需要复杂权限系统

当前采用“个人账号 + 默认个人工作区 + 资源所有者校验”。复用已有 `user_account`、`tenant`、`workspace`、`workspace_member` 表，不新增权限树、动态菜单权限或独立认证微服务。

数据库模式只选择账号在有效租户中具有 `OWNER` 成员关系的有效工作区。暂不让 `EDITOR` / `VIEWER` 以相同权限登录，避免未实现角色授权时默认放行写操作。没有符合条件的工作区时登录失败。未来开放团队时，应明确每类操作的角色规则，再支持成员登录和工作区切换。

`tenantId` 和 `workspaceId` 是对已有数据模型的兼容，不代表多租户管理功能已经完成。业务层只依赖 `CurrentActor`，未来切换认证方式不需要让 Plan 模块读取 Cookie 或解析 Token。

## 2. 认证链路

```text
邮箱或用户名 → user_identity(EMAIL / USERNAME / local) → user_account → BCrypt → 服务端 HttpSession
                                                              ↓
                                                     SecurityContextCurrentActor
                                                              ↓
                                            ActorIdentity(userId, tenantId, workspaceId)
                                                              ↓
                                            PlanningSessionService 所有权与版本校验
```

- `identity/application/CurrentActor.java`：唯一业务身份入口，`require()` 没有匿名降级身份。
- `identity/infrastructure/security/SecurityContextCurrentActor.java`：只接受已认证的 `AuthenticatedUser`，不信任请求中的 `userId`、`tenantId` 或自定义身份请求头。
- `DatabaseUserDetailsService`：带 `@` 的标识按规范化邮箱查找，其他标识按规范化用户名查找，再用稳定 `userId` 加载账户和默认工作区；无密码、锁定、禁用账户不能登录。
- `AuthenticationService`：校验密码后轮换已有 Session ID，显式保存 SecurityContext；退出时使当前 Session 失效。
- `EmailRegistrationService`：申请验证码、验证验证码、创建账户并在成功后消费验证码；不保存验证码明文。
- `HybridEmailVerificationStore`：使用 Redis Lua 脚本原子执行冷却、邮箱/IP 小时限流和尝试次数限制；生产配置下 Redis 故障会拒绝发码。
- `JdbcAccountRegistrationGateway`：在同一事务中创建个人租户、账户、默认工作区、OWNER 成员关系和已验证邮箱身份。
- `SecurityConfiguration`：除登录、CSRF 初始化和健康检查外，API 必须登录；写请求还必须通过 CSRF 校验。

使用 HttpOnly 的 `JSESSIONID` 保存浏览器会话标识，不把登录凭据或认证 Token 存入 localStorage。注册、登录和启动配置密码均至少 8 个字符，且 UTF-8 编码不超过 BCrypt 的 72 字节上限。登录输入超过该字节上限会被拒绝，避免截断导致不同密码被视为相同。

默认 Session 空闲超时 8 小时，可通过 `AUTH_SESSION_TIMEOUT` 调整；这不是固定的绝对有效期。当前为单实例 Servlet Session，重启后需要重新登录。

如果前端和 API 部署在不同域名，必须将 `AUTH_CORS_ALLOWED_ORIGIN` 设置为完整的前端 Origin，并在 HTTPS 环境设置 `AUTH_COOKIE_SECURE=true`、`AUTH_COOKIE_SAME_SITE=None`，否则浏览器不会携带登录 Session 和 CSRF Cookie。不要使用 `*` 作为允许来源。

## 3. 如何启动和登录

### 完整数据库模式

1. 启动现有 PostgreSQL / Redis 基础设施。
2. 在启动后端的进程环境中设置 `BOOTSTRAP_USER_PASSWORD`；不要把真实密码提交到 Git。
3. 默认初始化邮箱为 `demo@local`，对应 V3 已有的种子账户。`BOOTSTRAP_USER_EMAIL` 只能选择已存在账户，不会自动创建任意邮箱。
4. 启动后端。Flyway V5 创建 `user_identity`、迁移已有邮箱身份，并把身份唯一性从 `user_account.email` 转移到身份表；V9 扩展身份类型以支持用户名登录。
5. 初始化器仅在账户没有密码哈希时写入密码。再次设置环境变量不会覆盖既有密码，也不是重置密码接口。
6. 前端点击“登录”，输入邮箱或用户名及对应密码；访问工作台、历史或设置时也会先要求登录。

### 首位平台管理员

需要自动创建平台管理员时，在受控的启动环境中显式设置 `APP_SECURITY_BOOTSTRAP_ADMIN_ENABLED=true`，并通过 `APP_SECURITY_BOOTSTRAP_ADMIN_PASSWORD` 注入密码。初始化会创建两个独立账户：邮箱管理员默认使用 `1767443348@qq.com`、显示名 `Admin`；备用管理员默认使用用户名 `admin`，不绑定邮箱。可通过 `APP_SECURITY_BOOTSTRAP_ADMIN_USERNAME`、`APP_SECURITY_BOOTSTRAP_ADMIN_EMAIL`、`APP_SECURITY_BOOTSTRAP_ADMIN_DISPLAY_NAME` 覆盖对应标识。默认不启用初始化，也不在仓库内保存初始密码。

启动时，初始化器在事务中锁定 `platform_admin_bootstrap` 单例行，分别为两个管理员创建个人租户、`user_account`、默认工作区和 OWNER 成员关系；邮箱身份仅绑定邮箱管理员，用户名身份仅绑定备用管理员。两个账户各自保存以 BCrypt 工作因子 12 编码的密码哈希。若初始化标记已消费或平台已存在管理员，不会创建账户或重设密码；在 `APP_SECURITY_BOOTSTRAP_ADMIN_ENABLED=true` 时，会尝试把配置用户名绑定到与配置邮箱完全匹配的活动平台管理员，只有邮箱身份和账户均为活动状态、且该用户名尚未绑定时才创建关联。不会恢复已撤销身份或覆盖其他账户的绑定，未匹配或冲突会输出不含邮箱、用户名和密码的原因码。初始化成功后可从运行环境移除初始密码。已有 `APP_SECURITY_BOOTSTRAP_ADMIN_USER_ID` 是晋升既有账户的兼容路径，与新账户初始化共用一次性标记；该兼容路径不会自动创建备用账户。

登录界面接受邮箱或用户名。新请求使用 `identifier` 字段；服务端仍接受旧客户端提交的 `email` 字段作为兼容别名。

### 启用邮箱验证码注册

完整数据库模式需要 PostgreSQL 和 Redis。至少配置：

```text
EMAIL_REGISTRATION_ENABLED=true
EMAIL_VERIFICATION_SECRET=<至少 32 字节的随机密钥>
EMAIL_DELIVERY_MODE=smtp
EMAIL_FROM_ADDRESS=<发件邮箱>
SMTP_HOST=<SMTP 主机>
SMTP_PORT=587
SMTP_USERNAME=<SMTP 用户名>
SMTP_PASSWORD=<SMTP 密码或授权码>
```

本机调试可将 `EMAIL_DELIVERY_MODE=log`，验证码会写入后端警告日志；该模式不得用于公网。默认 `disabled` 会明确拒绝发码。SMTP 可以使用已有企业邮箱或邮件服务商提供的 SMTP，不要求绑定某一家验证码平台。

默认规则为同一邮箱 60 秒后才能重发、验证码 5 分钟过期、最多错误 5 次、同一邮箱每小时最多发 5 次、同一来源 IP 每小时最多发 20 次。所有规则由服务端执行。Redis 中只保存验证码的 HMAC 摘要和邮箱/IP 的 SHA-256 指纹，不保存验证码明文；账户创建成功后才消费验证码。

公网部署保持 `EMAIL_VERIFICATION_REQUIRE_REDIS=true`。只有明确的单实例开发环境才可设为 `false`；当邮件投递模式同时为 `log` 时，验证码生命周期直接使用进程内存，不连接 Redis，应用重启后验证码失效。IP 限流读取 Servlet 解析后的远端地址；部署在反向代理后必须只信任受控代理并正确配置 forwarded-header 处理，不能直接信任任意客户端传入的 `X-Forwarded-For`。

Spring Boot 不会自动把仓库根目录 `.env` 作为进程环境加载。使用 IDE 时在运行配置中设置环境变量；PowerShell 可以安全读取密码后启动：

```powershell
# 在 services/api 目录运行；密码不会以明文出现在命令历史里。
$env:BOOTSTRAP_USER_PASSWORD = Read-Host '设置初始账户密码' -MaskInput
mvn.cmd spring-boot:run
```

这里的 `-MaskInput` 需要 PowerShell 7；其他终端请使用 IDE 的受控环境配置。首次写入成功后可从运行配置中移除初始化密码。账户缺失或未配置密码会记录不含密码的提醒，不会生成通用默认密码。

如果历史数据库曾经存在跨租户重复邮箱，V4 已经会失败而不是任意选择一个用户；V5 因此可以安全地为每个已有账户创建唯一邮箱身份。应先审计并明确处理重复账户，不要自动删除或合并用户。

### 通用登录身份模型

`user_account.id` 仍是业务授权和资源所有权使用的稳定用户标识；邮箱、用户名、手机号和微信都只是找到该用户的登录身份。一个用户可以绑定多条身份，但同一个身份不能同时属于两个用户：

```text
user_account
  └── user_identity
        ├── EMAIL  / issuer=local / normalized_identifier=小写邮箱
        ├── USERNAME / issuer=local / normalized_identifier=小写用户名
        ├── PHONE  / issuer=local / normalized_identifier=E.164 手机号
        └── WECHAT / issuer=AppID / normalized_identifier=UnionID 或应用内 OpenID
```

身份表的重要字段：

| 字段 | 规则 |
| --- | --- |
| `identity_type` | 当前只允许 `EMAIL`、`PHONE`、`WECHAT`、`USERNAME` |
| `issuer` | 本地身份使用 `local`；微信身份使用实际开放平台应用标识，避免不同应用的 OpenID 冲突 |
| `identifier` | 身份标识的标准展示值，不保存 access token、验证码或 AppSecret |
| `normalized_identifier` | 登录查找和唯一约束使用的值；邮箱和用户名转小写，用户名为 3–32 位 ASCII 字母、数字、点、下划线或连字符；手机号必须先转 E.164，微信标识保持大小写 |
| `status` | `ACTIVE` 身份可用于认证，`REVOKED` 身份不可登录 |
| `verified_at` | 记录完成邮箱、短信或第三方授权验证的时间；历史种子账户迁移时为空 |

唯一索引覆盖 `(identity_type, issuer, normalized_identifier)`。这意味着同一邮箱或手机号不能注册两个平台用户，同一微信应用下的同一微信身份也不能重复绑定。`user_account.email` 暂时作为当前 API 的联系邮箱兼容字段保留，但已经允许为空，也不再承担认证唯一性；新增注册流程必须同时写入账户和身份，不能只写该兼容字段。

`UserIdentityKey` 集中定义身份规范化：邮箱和用户名去除首尾空白并转小写，用户名仅允许 3–32 位 ASCII 字母、数字、点、下划线或连字符；手机号拒绝非 E.164 值，微信的签发方和用户标识去除首尾空白但保留大小写。后续绑定接口仍必须先完成验证码或 OAuth 回调验证，再持久化身份；仓库层没有对外暴露“未经验证直接绑定”的 HTTP 接口。

### 无数据库 local-mock 模式

```powershell
# 在 services/api 目录运行
$env:LOCAL_AUTH_PASSWORD = Read-Host '设置本机联调密码' -MaskInput
mvn.cmd spring-boot:run '-Dspring-boot.run.profiles=local-mock'
```

默认邮箱仍为 `demo@local`，可用 `LOCAL_AUTH_EMAIL` 修改。该模式使用显式配置的密码和固定的本地用户 UUID，**不允许无密码启动**。它只适合本地单用户联调，不能用于多用户身份认证。Provider 为 Mock，不调用收费模型；历史持久化关闭。

前端在 `apps/web` 目录执行 `npm.cmd run dev`。开发服务器把 `/api` 代理到后端；生产也建议使用同源反向代理。当前没有开放跨域认证，直接把 API base URL 改为另一个站点并不足以完成跨域部署。

HTTPS 部署必须设置 `AUTH_COOKIE_SECURE=true`，同时保证代理配置正确。`JSESSIONID` 和 `XSRF-TOKEN` 都使用 SameSite=Lax；本机 HTTP 联调保持 Secure=false。Session 只通过 Cookie 传输，不接受 URL 中的 Session ID。

## 4. API 契约

统一响应仍为项目原有 `ApiResponse` / `ApiErrorResponse`。

| 接口 | 是否需要登录 | 说明 |
| --- | --- | --- |
| `GET /api/v1/auth/csrf` | 否 | 生成 CSRF Cookie，返回 `headerName`、`parameterName`、`token` |
| `POST /api/v1/auth/registration-code` | 否，但需要 CSRF | JSON：`email`；发送 6 位验证码并返回重发/过期秒数 |
| `POST /api/v1/auth/register` | 否，但需要 CSRF | JSON：`email`、`verificationCode`、`password`；成功后自动登录 |
| `POST /api/v1/auth/login` | 否，但需要 CSRF | JSON：`identifier`（邮箱或用户名）、`password`；兼容旧字段 `email`；成功返回当前用户并保存 Session |
| `GET /api/v1/auth/me` | 是 | 返回 `userId`、`tenantId`、`workspaceId`、`email`、`displayName` |
| `POST /api/v1/auth/logout` | 是，且需要 CSRF | 使当前 Session 失效并清除 CSRF Cookie |

浏览器调用顺序：先访问 `/auth/csrf`，再发送登录请求；Axios 从 `XSRF-TOKEN` Cookie 读取值，通过 `X-XSRF-TOKEN` 请求头发送。登录成功会重新下发 CSRF Token，随后写请求必须使用新值。退出后再次登录前重新获取 Token。非浏览器客户端同样需要维护 Cookie 容器并提交 CSRF 请求头。

- 未登录访问受保护接口：`401 AUTHENTICATION_REQUIRED`。
- 邮箱/用户名或密码错误、账户不可登录：`401 AUTHENTICATION_FAILED`，不披露账户状态细节。
- 重复发码、邮箱小时限流或 IP 小时限流：`429`，响应含 `Retry-After` 和 `details.retryAfterSeconds`。
- 验证码错误、过期或尝试次数用尽：`400`；邮箱已经注册：`409 EMAIL_ALREADY_REGISTERED`。
- 邮件投递或生产 Redis 不可用：`503`，不会绕过限流继续注册。
- CSRF 缺失或不匹配：`403 ACCESS_DENIED`。
- 写请求同时缺失认证和 CSRF 时，安全链可能先返回 403；不要仅凭该状态判断账号是否登录。
- 前端遇到业务接口 401 会重新加载登录页，清除页面内存，不自动重放失败请求。未提交草稿不会自动恢复；Plan 的 30 分钟过期恢复不属于本轮。

登录和退出均重新装载页面，避免继续复用当前页面中的文件、计划和回答。浏览器本地项目索引仍属于该浏览器配置文件的本地存储，不应把它当成不同本机使用者之间的安全隔离；共用电脑时应使用独立浏览器配置文件。

## 5. Plan 所有权规则

`ContextSession` 和 `PlanSession` 都有必填 `ownerUserId`，取值只来自 `CurrentActor.require().userId()`。使用全局唯一用户 UUID 做个人资源隔离，同一租户、同一工作区的另一账号也不能使用这些资源。

| 环节 | 校验 |
| --- | --- |
| 准备上下文 | 认证后分析，保存当前用户 ID |
| 引用上下文生成问题 | 先校验所有者，再校验版本和原需求 |
| 注册计划 | 再次核对上下文引用的所有者与原需求，保存计划所有者 |
| 确认答案 | 校验计划所有者、原需求、上下文引用和上下文所有者，以及完整问题集合 |
| 复用快照 | 再次校验上下文所有者；快照复用仍要求检索条件与版本一致 |

没有引入前端可修改的身份字段，也没有把指纹或随机 ID 当成授权凭据。已认证用户访问其他人的资源时，与不存在/过期保持相同的 400 错误，避免泄露资源是否存在。正文和摘要都不会随错误返回。

Redis key 使用 `prompt-optimizer:planning-context:v2:` 和 `prompt-optimizer:plan:v2:`，不读取旧的无所有者数据。旧数据保留原 TTL 自然清理；升级前创建的计划需要重新生成。不要同时对外运行旧的无认证版本。

Redis 和内存存储都保存 owner 字段，授权统一在服务层完成。Redis 不可用时的多实例一致性策略没有在本轮改动。同一账号重新登录后仍可访问尚未过期的 Plan，而不是把资源绑死到某一次浏览器会话。

## 6. 测试

```powershell
# services/api
mvn.cmd test

# apps/web
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e
```

- `AuthenticationIntegrationTest`：实际 BCrypt 认证、Session 轮换、凭据擦除、CSRF、退出、伪造身份、两个同工作区账号的 Plan 越权拒绝，以及同账号重新登录后可继续使用。
- `IdentitySecurityTest`：通过身份表加载账户、账户/工作区缺失、初始化幂等、超长密码拒绝。
- `UserIdentityKeyTest`：邮箱、E.164 手机号、微信签发方与第三方主体的规范化边界。
- `InMemoryEmailVerificationStoreTest`：重发间隔、过期、最大尝试次数、邮箱/IP 小时限流与投递失败回滚。
- `EmailRegistrationServiceTest`：发码、邮箱规范化、事务网关调用、验证码消费和 BCrypt 长度边界。
- `PlanningSessionServiceTest`：所有者、需求、版本、TTL、注册和快照复用边界。
- 原有 Controller 契约测试加载真实安全过滤链，显式提供测试身份和 CSRF，没有禁用过滤器。
- 前端 auth store / Playwright：匿名路由、错误密码重试、刷新、退出、后端不可用，以及现有工作台流程。

`auth-real.spec.ts` 默认跳过，避免连接未知账户。真实浏览器联调需要先启动独立的 local-mock 后端，再设置：

```powershell
# apps/web；与测试后端使用相同的 LOCAL_AUTH_PASSWORD
$env:AUTH_E2E_REAL = 'true'
$env:LOCAL_AUTH_PASSWORD = Read-Host '输入测试后端密码' -MaskInput
$env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:9001'
$env:PROMPT_OPTIMIZER_E2E_PORT = '5186'
npm.cmd run test:e2e -- auth-real.spec.ts
```

后端测试不连接真实 PostgreSQL 或 Redis。数据库迁移执行、真实 Redis 故障切换和 HTTPS 反向代理环境需单独验证，不能用 MockMvc 通过代替这些部署验证。

本机 Windows / JDK 21 联调如果在 Tomcat 启动时报 `UnixDomainSockets.connect: Invalid argument`，可以创建一个项目内临时目录，并仅给该次启动追加 `-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=<该目录绝对路径>`。本轮使用该方式成功启动并进行了真实浏览器验证；不要为此关闭认证、CSRF 或修改系统级网络策略。

## 7. 仍不属于生产权限系统

本轮完成的是可信身份入口和 Plan 资源隔离。公网或多实例上线前仍应处理：

- 登录限流、防暴力破解、审计与监控。
- 分布式登录 Session、会话撤销和账户禁用后的即时失效。目前已有 Session 不会逐请求重查账户状态，紧急禁用需同时撤销会话。
- 上传文档 `documentId` 等其他临时资源的完整所有权审计；不能从 Plan ID 已隔离推导全部资源均已隔离。
- 团队角色、邀请、工作区切换和跨成员资源共享策略。
- 改密、找回密码、邮箱换绑与登录防暴力破解；注册验证码限流不能替代登录接口限流。

历史记录已从固定演示身份切换到 `CurrentActor` 的默认租户/工作区作用域，但仍是工作区资源，不应当作逐记录私有权限系统。

实现依据：Spring Security 关于 [CSRF 与 SPA 登录后 Token 刷新](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) 的说明。实际代码依赖版本由项目 Spring Boot BOM 管理。
