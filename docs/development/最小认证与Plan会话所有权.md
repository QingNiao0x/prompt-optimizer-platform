# 最小认证与 Plan 会话所有权

本轮实现邮箱密码登录、退出、当前用户读取，以及 `contextId` / `planId` 的服务端所有权绑定。没有实现公开注册、微信登录、找回密码、团队邀请或角色管理后台。

## 1. 为什么目前不需要复杂权限系统

当前采用“个人账号 + 默认个人工作区 + 资源所有者校验”。复用已有 `user_account`、`tenant`、`workspace`、`workspace_member` 表，不新增权限树、动态菜单权限或独立认证微服务。

数据库模式只选择账号在有效租户中具有 `OWNER` 成员关系的有效工作区。暂不让 `EDITOR` / `VIEWER` 以相同权限登录，避免未实现角色授权时默认放行写操作。没有符合条件的工作区时登录失败。未来开放团队时，应明确每类操作的角色规则，再支持成员登录和工作区切换。

`tenantId` 和 `workspaceId` 是对已有数据模型的兼容，不代表多租户管理功能已经完成。业务层只依赖 `CurrentActor`，未来切换认证方式不需要让 Plan 模块读取 Cookie 或解析 Token。

## 2. 认证链路

```text
邮箱密码 → Spring Security / BCrypt → 服务端 HttpSession
                                      ↓
                             SecurityContextCurrentActor
                                      ↓
                    ActorIdentity(userId, tenantId, workspaceId)
                                      ↓
                    PlanningSessionService 所有权与版本校验
```

- `identity/application/CurrentActor.java`：唯一业务身份入口，`require()` 没有匿名降级身份。
- `identity/infrastructure/security/SecurityContextCurrentActor.java`：只接受已认证的 `AuthenticatedUser`，不信任请求中的 `userId`、`tenantId` 或自定义身份请求头。
- `DatabaseUserDetailsService`：按规范化邮箱查账户，并从数据库成员关系选择工作区；无密码、锁定、禁用账户不能登录。
- `AuthenticationService`：校验密码后轮换已有 Session ID，显式保存 SecurityContext；退出时使当前 Session 失效。
- `SecurityConfiguration`：除登录、CSRF 初始化和健康检查外，API 必须登录；写请求还必须通过 CSRF 校验。

使用 HttpOnly 的 `JSESSIONID` 保存浏览器会话标识，不把登录凭据或认证 Token 存入 localStorage。密码只以 BCrypt 哈希保存，登录成功后 Principal 中的哈希也会被擦除。初始化密码至少 12 个字符，且 UTF-8 编码不超过 BCrypt 的 72 字节上限。登录输入超过该字节上限会被拒绝，避免截断导致不同密码被视为相同。

默认 Session 空闲超时 8 小时，可通过 `AUTH_SESSION_TIMEOUT` 调整；这不是固定的绝对有效期。当前为单实例 Servlet Session，重启后需要重新登录。

## 3. 如何启动和登录

### 完整数据库模式

1. 启动现有 PostgreSQL / Redis 基础设施。
2. 在启动后端的进程环境中设置 `BOOTSTRAP_USER_PASSWORD`；不要把真实密码提交到 Git。
3. 默认初始化邮箱为 `demo@local`，对应 V3 已有的种子账户。`BOOTSTRAP_USER_EMAIL` 只能选择已存在账户，不会自动创建任意邮箱。
4. 启动后端。Flyway V4 为邮箱增加全局忽略大小写唯一约束，确保无需客户端提供租户 ID 就能确定登录身份。
5. 初始化器仅在账户没有密码哈希时写入密码。再次设置环境变量不会覆盖既有密码，也不是重置密码接口。
6. 前端点击“登录”，输入邮箱和设置的密码；访问工作台、历史或设置时也会先要求登录。

Spring Boot 不会自动把仓库根目录 `.env` 作为进程环境加载。使用 IDE 时在运行配置中设置环境变量；PowerShell 可以安全读取密码后启动：

```powershell
# 在 services/api 目录运行；密码不会以明文出现在命令历史里。
$env:BOOTSTRAP_USER_PASSWORD = Read-Host '设置初始账户密码' -MaskInput
mvn.cmd spring-boot:run
```

这里的 `-MaskInput` 需要 PowerShell 7；其他终端请使用 IDE 的受控环境配置。首次写入成功后可从运行配置中移除初始化密码。账户缺失或未配置密码会记录不含密码的提醒，不会生成通用默认密码。

如果历史数据库已经存在跨租户重复邮箱，V4 会失败而不是任意选择一个用户。应先审计并明确处理重复账户，不要自动删除或合并用户。

### 无数据库 local-mock 模式

```powershell
# 在 services/api 目录运行
$env:LOCAL_AUTH_PASSWORD = Read-Host '设置本机联调密码' -MaskInput
mvn.cmd spring-boot:run '-Dspring-boot.run.profiles=local-mock'
```

默认邮箱仍为 `demo@local`，可用 `LOCAL_AUTH_EMAIL` 修改。该模式使用显式配置的密码和固定的本地用户 UUID，**不允许无密码启动**。它只适合本地单用户联调，不能用于多用户身份认证。Provider 为 Mock，不调用收费模型；历史持久化关闭，数据库 Provider 配置管理接口不加载。

前端在 `apps/web` 目录执行 `npm.cmd run dev`。开发服务器把 `/api` 代理到后端；生产也建议使用同源反向代理。当前没有开放跨域认证，直接把 API base URL 改为另一个站点并不足以完成跨域部署。

HTTPS 部署必须设置 `AUTH_COOKIE_SECURE=true`，同时保证代理配置正确。`JSESSIONID` 和 `XSRF-TOKEN` 都使用 SameSite=Lax；本机 HTTP 联调保持 Secure=false。Session 只通过 Cookie 传输，不接受 URL 中的 Session ID。

## 4. API 契约

统一响应仍为项目原有 `ApiResponse` / `ApiErrorResponse`。

| 接口 | 是否需要登录 | 说明 |
| --- | --- | --- |
| `GET /api/v1/auth/csrf` | 否 | 生成 CSRF Cookie，返回 `headerName`、`parameterName`、`token` |
| `POST /api/v1/auth/login` | 否，但需要 CSRF | JSON：`email`、`password`；成功返回当前用户并保存 Session |
| `GET /api/v1/auth/me` | 是 | 返回 `userId`、`tenantId`、`workspaceId`、`email`、`displayName` |
| `POST /api/v1/auth/logout` | 是，且需要 CSRF | 使当前 Session 失效并清除 CSRF Cookie |

浏览器调用顺序：先访问 `/auth/csrf`，再发送登录请求；Axios 从 `XSRF-TOKEN` Cookie 读取值，通过 `X-XSRF-TOKEN` 请求头发送。登录成功会重新下发 CSRF Token，随后写请求必须使用新值。退出后再次登录前重新获取 Token。非浏览器客户端同样需要维护 Cookie 容器并提交 CSRF 请求头。

- 未登录访问受保护接口：`401 AUTHENTICATION_REQUIRED`。
- 邮箱密码错误或账户不可登录：`401 AUTHENTICATION_FAILED`，不披露账户状态细节。
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
- `IdentitySecurityTest`：身份来源、账户/工作区缺失、初始化幂等、超长密码拒绝。
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
- 公开注册、邮箱验证、改密、找回密码。

历史记录和 Provider 配置已从固定演示身份切换到 `CurrentActor` 的默认租户/工作区作用域，但仍是工作区资源，不应当作逐记录私有权限系统。

实现依据：Spring Security 关于 [CSRF 与 SPA 登录后 Token 刷新](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) 的说明。实际代码依赖版本由项目 Spring Boot BOM 管理。
