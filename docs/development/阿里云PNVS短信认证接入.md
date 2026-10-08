# 阿里云 PNVS 短信认证接入

本次实现日期：2026-10-08。此文描述当前代码，不代表云端资质、真实投递或正式部署已验收。功能默认关闭，关闭时不读取 AccessKey，也不改变邮箱注册和邮箱/用户名密码登录。

## 支持范围

- 中国大陆手机号注册：创建个人租户、账户、默认工作区及 OWNER 成员关系，保存 BCrypt 密码；不伪造联系邮箱。
- 手机号密码登录；普通账号短信登录。未注册号码不会在登录时自动开户，管理员不能只凭短信登录。
- 设置页“账户安全”首次绑定手机号：发码和提交都重新验证当前密码、账户及当前登录身份状态；已有邮箱和新手机号指向同一账户。
- 一个号码只属于一个账户，已撤销身份也继续占用号码；每账户最多一个 ACTIVE 手机身份。占用返回冲突，不转移身份，不合并历史。
- 不包含修改/解绑手机号、密码重置、微信或账号合并，仅启用 100001（注册/登录）与 100004（首次绑定）模板。

密码沿用统一 `PasswordPolicy`：至少 8 个字符、包含字母和数字、UTF-8 编码不超过 72 字节。手机号输入统一规范化为 `+86…`，保存为 `PHONE/local`，不把短信供应商作为 issuer。

## 后端环境配置

参考根目录 `.env.example` 的空值变量名。以下真实值只在后端进程环境中配置，不填写到源码、截图、聊天、测试报告、`VITE_*` 或提交到 Git 的 IDE 配置中。Spring Boot 不会因为存在 `.env.example` 就自动加载它。

| 变量 | 要求 |
| --- | --- |
| `SMS_ENABLED` | 准备好下列配置后设为 `true`；默认 `false` |
| `ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET` | 已获 PNVS 发送/核验权限的 RAM 用户凭据；仅通过后端 `System.getenv` 读取，不使用主账号密钥 |
| `SMS_SIGN_NAME` | 云端实际可用的签名；公开示例不填写部署签名 |
| `SMS_SCHEME_PREFIX` | 每个环境不同，1–11 位字母/数字/下划线/连字符；如开发、测试、生产分别使用不同前缀 |
| `SMS_VERIFICATION_SECRET` | 至少 32 字节的独立随机密钥；不可复用 AccessKey 或邮箱验证码摘要密钥，多实例保持一致 |
| `SMS_ACCOUNT_TEMPLATE` | 默认 `100001`，注册和登录 |
| `SMS_BINDING_TEMPLATE` | 默认 `100004`，首次绑定 |
| `SMS_PHONE_HOURLY_LIMIT` / `SMS_IP_HOURLY_LIMIT` / `SMS_ACTOR_HOURLY_LIMIT` | 默认 `5` / `20` / `5`，必须为正数 |
| `REDIS_URL` | 可用的共享 Redis；短信限流与图形验证码不回退单机内存 |
| `LOGIN_GUARD_REQUIRE_REDIS` | 必须为 `true`（默认值） |

Windows 用户环境变量修改后，完全退出并重启 IDEA，再启动后端；仅重启已打开 IDEA 内的应用未必刷新父进程环境。启用成功时后端输出 `event=sms.configuration.checked credentialsConfigured=true mode=pnvs`，只确认变量存在和客户端可创建，不证明云端权限或投递成功，也不输出变量值。

真实短信禁止与 `local-sql-debug`、`local-mock`、P6Spy、MyBatis 标准输出参数日志及身份 Mapper / SDK / HTTP 的 DEBUG/TRACE 同时启用。启动校验会拒绝这些组合；不要临时打开请求/响应正文日志排查真实验证码。公网必须使用 HTTPS，并设置 `AUTH_COOKIE_SECURE=true`；同源部署保持 `SameSite=Lax` 和现有 CSRF 机制。

来源 IP 使用后端 `request.getRemoteAddr()`，不自行信任浏览器的 `X-Forwarded-For`。有反向代理时由部署配置严格限定可信代理并恢复实际来源地址；未配置时所有用户可能共享代理 IP 的 20 次预算。不要为缓解误限流而无条件信任任意转发头。

## 接口契约

统一成功响应仍为 `{requestId, data}`，错误为 `{requestId, error}`。写请求先取得 `/api/v1/auth/csrf` Cookie，再携带对应 CSRF Header；能力查询不是授权凭据，关闭开关后直接调用手机接口仍被拒绝。

| 接口 | 请求关键字段 / 行为 |
| --- | --- |
| `GET /api/v1/auth/capabilities` | 仅返回 `phoneRegistration`、`smsLogin`、`phoneBinding` 布尔值，不返回云配置 |
| `POST /api/v1/auth/sms/challenges` | `phone`、`purpose: REGISTER或LOGIN`、`captcha`；返回 `challengeId`、`expiresInSeconds:300`、`resendAfterSeconds:60` |
| `POST /api/v1/auth/phone/register` | `phone`、`challengeId`、`verificationCode`、`password`；注册后通过真实密码建立会话 |
| `POST /api/v1/auth/phone/login` | `phone`、`challengeId`、`verificationCode`；仅登录已有普通账户 |
| `POST /api/v1/me/phone-binding/challenges` | `phone`、`captcha`、`currentPassword`；必须已登录，不接受目标 `userId` |
| `POST /api/v1/me/phone-binding` | `phone`、`challengeId`、`verificationCode`、`currentPassword`；成功返回更新后的当前用户 |
| `POST /api/v1/auth/login` | 原字段不变，手机密码入口额外传 `identityType: PHONE`；旧请求中数字用户名不自动解释为号码 |
| `GET /api/v1/auth/me` | 增加 `phoneBound`、`maskedPhone`，如 `+86 138****0000`；无绑定时号码为空 |

表单验证码只保存在内存，不放 URL、localStorage 或日志。邮箱和手机号共用“创建账号”表单：按账号格式自动切换验证码接口，手机号发码前显示图形验证码，不额外显示 +86 输入前缀；短信能力关闭时只提示邮箱注册。切换账号会清除旧验证码及短信挑战，保留短信发送冷却。密码登录主表单显示“手机号或邮箱登录”，识别大陆手机号后显式提交 `identityType: PHONE`，其他输入保留邮箱/用户名解析；形似手机号的数字用户名可选择“使用用户名登录”，不会失败后自动重放。手机号密码登录不发送短信，既有短信登录及专用手机密码入口继续保留。设置页更新 Pinia 中的安全资料，不改变工作台三栏、历史和项目上下文流程。

| 典型错误 | HTTP / 处理 |
| --- | --- |
| 号码格式、验证码错误/过期/用途或浏览器不匹配 | 400，重新核对或获取验证码 |
| 未注册或不可用账户的短信登录 | 401 `PHONE_AUTHENTICATION_FAILED`；不自动创建账户 |
| 当前密码不正确或绑定账户不可用 | 403 `CURRENT_PASSWORD_INVALID`；不允许绑定 |
| 管理员短信登录 | 403 `SMS_PASSWORD_LOGIN_REQUIRED`；改用密码登录 |
| 号码属于其他账户，包括撤销身份 | 409 `PHONE_ALREADY_BOUND`；短信验证通过后才明确提示，不披露对方资料 |
| 当前账户已经绑定其他手机号 | 409 `PHONE_BINDING_EXISTS`；首期不支持换绑 |
| 正在被另一个请求核验 | 409 `SMS_VERIFICATION_IN_PROGRESS`；不能并发再次调用云核验 |
| 本地或云端限流 | 429，包含 `Retry-After` 和 `error.details.retryAfterSeconds` |
| 短信服务关闭、Redis 限流异常、云调用超时 | 503；不自动重发、不退还预算 |

占用的固定提示为：“该手机号已绑定其他账号，无法绑定到当前账号。请使用原账号登录，或更换手机号。”客户端提交其他 `userId` 不会改变服务端绑定目标。

## 云调用、限流和一致性

使用官方 `dypnsapi20170525` Java SDK 2.0.0，以 HTTPS 调用 `dypnsapi.aliyuncs.com`。验证码由 PNVS 生成：`{"code":"##code##","min":"5"}`，6 位数字、300 秒有效、60 秒间隔、重发覆盖旧码、`ReturnVerifyCode=false`，客户端和供应商自动重试均关闭。只有 `Success=true`、`Code=OK` 且 `Model.VerifyResult=PASS` 才接受核验。依据：[发送接口](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode)、[核验接口](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-checksmsverifycode)。

方案名为环境前缀加 `-register`、`-login`、`-bind`，发送和核验使用同一保存值。Redis Lua 原子预留发送预算：号码 60 秒冷却，号码 5 次/小时、来源 IP 20 次/小时，绑定再加当前用户 5 次/小时。小时预算采用首次发送起的固定一小时窗口；不同用途共享预算。失败、超时、号码占用都不重置预算。图形验证码有五分钟有效期，由 Redis 原子取出并删除，错误答案也消费图片挑战。

业务链路：

```text
图形验证码 + 必要的当前密码验证
→ Redis 原子预留预算
→ 数据库建立用途/号码HMAC/浏览器HMAC/操作者绑定的挑战
→ 事务外调用云发送、记录受理状态
→ 原子预留唯一核验者（每次增加尝试次数，最多5次）
→ 事务外调用云核验，只接受PASS
→ 持久化VERIFIED授权
→ 同一数据库事务复核身份、注册或绑定、消费挑战并保存结果
```

`sms_verification_challenge` 不保存验证码、密码或明文号码。状态为 `SENDING/SENT/VERIFYING/VERIFIED/CONSUMED/FAILED/SUPERSEDED`；重新发送替换同号码同用途的旧授权，云端迟到响应不能恢复失效挑战。数据库成功后没有必须成功的 Redis 清理步骤；密码认证后的失败计数清理失败只保留更严格的旧计数。

原浏览器、同用途和同操作者可在原五分钟有效期内重试注册/绑定，恢复已保存结果，不重复建号、改密码或绑定。注册恢复后自动登录仍校验数据库中的真实密码；短信登录挑战一旦消费，不能用同一个验证码重新建立第二个登录会话。会话保存失败时可查当前登录状态或重新登录，不自动重放短信认证。

云结果不确定时挑战终止，程序不再次发送或核验；进程在核验中退出造成的 `VERIFYING` 挑战不会被另一个请求接管，用户需等待冷却后重新发码。数据库事务失败但授权已保存为 `VERIFIED` 时，在绑定和有效期仍满足的前提下可恢复提交。

## 数据迁移与上线边界

新增 `V13__sms_verification_challenges.sql`，创建挑战及结果表，添加“每账户一个有效手机身份”和 PHONE/local/E.164 约束及注释。旧 Flyway 迁移未回改。由应用对目标库运行 Flyway；不要再手工建表后重复执行迁移。

发布前在备份/预发布库确认不存在多个 ACTIVE 手机身份、非 local 的 PHONE 或不规范号码。新约束遇到历史违规数据会拒绝迁移，不自动修正、删除或接管数据。短信功能关闭也会正常执行结构迁移；本次只在独立测试数据库运行迁移，未操作开发者原业务数据库。

本次不涵盖所有普通会话的即时撤销、邮箱验证码旧流程的一致性改造、邮箱数据库纵深规范化或账号合并。挑战记录的定期保留期清理仍需部署运维安排，不提供用户物理删除接口。HMAC 密钥变更会使短时挑战不可恢复并改变限流键；轮换应停发、等待窗口结束后统一切换实例，不能把轮换当作刷新发送预算。

代码中的启动校验不能替代持续的生产日志策略；不要在运行后通过动态日志配置重新开启敏感参数日志。本次仅检查变更路径，不代表整个仓库、Git 历史或现有部署已完成隐私审计。已有通用数据库默认口令必须在上线时用环境密钥覆盖。

## 自动验证与真实验收

自动测试使用合成账号及模拟 PNVS，**不读取实际 AccessKey，不发送短信**。

本轮结果：后端 59 项定向测试通过（其中真实数据库专项 19 项，无跳过）；前端 15 项单元测试、类型检查与生产构建通过；桌面/手机视口共 22 项浏览器用例通过。首轮浏览器断言虽通过，但受限进程清理阶段报错并退出非零，因此不计为最终验收；使用独立端口重跑后正常退出。构建仍有现有管理分析大分块和第三方 PURE 注释提示，未在短信任务中改动无关打包策略。

本轮变更范围敏感模式扫描覆盖 59 个文件，未发现实际 AccessKey、用户提供的真实联系标识或部署签名；前端构建产物不包含后端短信凭据/签名/业务密钥配置字段。此扫描是本轮限定检查，不能证明 Git 历史或整个仓库不存在其他敏感信息。

- 后端定向集：SDK 参数和脱敏、限流故障、图形验证码、邮箱注册回归、身份及管理员权限、原密码认证、新短信事务与 HTTP 安全流程。
- `SmsDatabaseAcceptanceTest`：真实 PostgreSQL 16、隔离随机 schema，模拟云供应商；专用本机 URL `jdbc:postgresql://127.0.0.1:55439/pnvs_acceptance`，Redis `127.0.0.1:56389`，数据库用户 `pnvs_test`。通过 `SMS_TEST_DB_URL` 显式开启，否则该类跳过，不能把跳过当作通过。
- 该次本机 Redis 可执行版本为 3.2，验证了实际 Lua/TTL/并发消费；生产 Redis 7、真实多实例 Spring Session 及公网代理 Cookie 链路仍需预发布验收。HTTP 会话验收采用 MockMvc，不是已部署浏览器到真实云的验收。
- 前端：认证工具/Pinia 单元测试、类型检查、构建及 Playwright 桌面/手机视口流程，浏览器接口均模拟，覆盖新入口和原邮箱登录回归。

测试命令示例（从对应子项目目录执行，测试数据库需事先独立启动）：

```text
mvn -Dtest=SmsDatabaseAcceptanceTest,AliyunPnvsProviderTest,RedisSmsRateLimiterTest,LoginProtectionTest,IdentitySecurityTest,UserAccountParameterBindingTest,PlatformAdminAccessTest,*EmailRegistration*Test,*EmailVerification*Test,AuthenticationIntegrationTest test
npm test -- src/stores/auth.test.ts src/features/auth
npm run typecheck
npm run build
npm run test:e2e -- e2e/phone-auth.spec.ts e2e/auth.spec.ts
```

最后必须另行确认真实测试号码与最多发送次数，由使用者在本机输入验证码：验收 PNVS 权限、签名、模板、三种 SchemeName、费用/预算提醒、真实短信时延和一次性验证。不得把验证码或密钥发到聊天，也不得仅依据 SDK 编译或模拟测试宣布云端投递成功。
