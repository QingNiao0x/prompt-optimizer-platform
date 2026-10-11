# 模型调用对账与 Token 排行

## 1. 已实现的口径

新增 `MODEL_CALL_RECORDED`，独立记录模型适配器的一次调用尝试。与原有「直接增强提交次数」「Plan 完成次数」分开：一次业务提交可能产生 Plan 提问、最终增强、文档摘要、向量化及多次修复尝试；不能用业务次数对齐供应商请求次数。

- 操作：`prompt.optimize`、`plan.generate`、`document.summary.map`、`document.summary.reduce`、`context.embedding`；未知操作折叠为 `unknown`。
- 每次重试有独立事件 UUID；同一尝试重复终态上报只采集一次，journal 重放使用原 UUID，数据库 `ON CONFLICT DO NOTHING` 防止重放重复计量。
- `successful` 表示模型适配器和本次校验成功，不能代表后续历史保存或整个业务流程成功。失败、截断、`INVALID_RESPONSE`、HTTP 429/502 的有效已报告 Token 不回滚。
- 统计按调用开始时刻归属；跨日结束仍归入开始日。供应商可能采用不同记账时刻，跨日对账须核对两边定义。
- Token 来自上游 `usage` 的明确整数，不按字符数估算。缺失为 JSON null；负数、浮点、字符串、溢出及相互矛盾的数字标记 `INVALID`，不参与消费合计，不影响原本有效的模型结果。
- `COMPLETE`：输入、输出、总计全部有效；`PARTIAL`：仅部分已报告；`UNKNOWN`：三类均缺失；`INVALID`：有非法或矛盾值。向量接口没有输出 Token 时可以是 PARTIAL，同时保留已报告总量。
- 缓存使用 DeepSeek/OpenAI 兼容扩展 `prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`。HIT/MISS/MIXED/NONE/UNKNOWN 分别表示全命中、未命中、部分命中、已报告零输入与未知。未提供该扩展的供应商缓存状态保持未知。

## 2. 持久化与隐私边界

复用 `audit_event` 与现有 `AuditEventDelivery`，无需数据库结构迁移，不回改 Flyway。现有表支持空操作者；`event_type` 没有数据库 CHECK；JSONB 没有固定键约束。

| 字段 | 内容与边界 |
| --- | --- |
| id | 单次尝试 UUID，投递重放不变 |
| tenant_id | 从服务端 `CurrentActor` 取得，不能由业务请求指定 |
| actor_user_id | 此事件强制为 NULL；已有账号业务事件仍必须携带账号 |
| occurred_at | 调用开始的 UTC 时刻 |
| details.schemaVersion | 固定整数 1 |
| requestId | UUID；非 UUID 请求号保存 `hash-` 加不可逆短指纹，查询可传原号或已保存指纹 |
| workflowId | 20 位十六进制流程指纹；不保存原始 planId/contextId/documentId |
| operation、providerRoute、model | 固定操作代码、服务端配置的公开路由/模型标识 |
| attempt、mock、successful、failureType | 尝试编号 1–100、模拟标记、结果、固定失败代码 |
| upstreamStatus、willRetry | 已知 HTTP 状态或 null；是否将进行结构修复重试 |
| transportState | NOT_SENT / STARTED / RESPONSE_RECEIVED / MOCK / UNKNOWN |
| usageState、cacheState | 用量完整性与缓存证据状态 |
| inputTokens、outputTokens、totalTokens | 非负整数或 null，单位 Token |
| cacheHitTokens、cacheMissTokens | 已报告缓存输入 Token 整数或 null |
| durationMs | 非负毫秒 |

不保存用户 ID、邮箱、显示名称、IP、登录会话、提示词、答案、文件、端点、凭据或原始异常消息。不新增带用户、请求或流程标签的 Micrometer 指标。安全关联号用于定位同一次流程，不提供按账号关联 Token 的查询。

HTTP 拦截器先绑定可信租户，模型日志终态采集数字快照；文档异步任务显式捕获租户范围，在工作线程恢复，结束后清除。不会复制整个 SecurityContext。后台或独立工具若调用模型，需通过同一受信任上下文显式绑定租户；没有可信范围时不伪造租户归属。

采集先写持久 journal，再由独立事务入库，投递成功前查询可能暂不可见。已有投递状态面板显示积压/故障；不增加 Redis 或进程内统计缓存，不做自动清理。数据库故障后的恢复沿用原 UUID；磁盘与数据库同时故障、或进程在终态采集之前被强制终止，不能宣称仍具备完整调用账本。

`ModelCallLogger` 保留原成功/失败日志字段及顺序。用量先于内容解析保留；HTTP 错误响应只在内存内从最多 64 KiB 的 JSON 提取整数，不输出正文或 Jackson 异常片段。无法取得用量仍保留失败类别与未知标记。

## 3. 管理员接口

全部位于 `/api/v1/admin/analytics`：Spring Security 未登录 401、普通账号 403；Controller 再调用 `PlatformAdminAccess.require()` 复核数据库当前角色，旧会话角色不能绕过撤销。

### Token 排行

`GET /token-ranking?period=DAY&date=2026-10-11&scope=GLOBAL&groupBy=OPERATION&limit=20`

| 参数 | 规则 |
| --- | --- |
| period | 必填 DAY / WEEK / MONTH，展开完整自然日、周、月；周一为起点 |
| date | 必填 YYYY-MM-DD，周期内锚点日，复用统计时区与日期边界校验 |
| scope | GLOBAL 默认，或 TENANT |
| tenantId | TENANT 必填 UUID；GLOBAL 携带此字段返回 400，防止静默忽略范围 |
| groupBy | OPERATION 默认，或 SOURCE（公开路由 + 模型） |
| limit | 1–100，默认 20 |

响应 `ApiResponse.data`：`period`、`scope`、`tenantId`、`groupBy`、`ranking[]`。

排行项包含 `source`、`callCount`、`successCount`、`failureCount`、`completeUsageCount`、`partialUsageCount`、`unknownUsageCount`、`invalidUsageCount`、`cacheKnownUsageCount`、`cacheUnknownUsageCount` 及五类 Token 合计。

只排行非 Mock、且没有明确 NOT_SENT 证据的尝试，包括失败与未知传输状态。按已报告有效总 Token 降序、调用数降序、来源名排序，避免分页/Top N 同量不稳定。缺失用量不是零消费：零合计只表示没有可加的数字，必须一起读取覆盖计数。缓存覆盖计数表示两类缓存数均已报告且有效的调用数与其余调用数。

### 调用明细

`GET /model-calls?fromDate=2026-10-11&toDate=2026-10-11&scope=GLOBAL&current=1&size=20`

- 日期必填，结束日包含在内，最多 366 自然日；数据库使用当地午夜转换的左闭右开区间，保留 DST 语义。
- scope/tenantId 同上；可选固定 `operation`、安全 `requestId`，不支持账号、邮箱或名称过滤。
- current 1–100000，默认 1；size 1–100，默认 20。
- 响应：`period`、`scope`、`tenantId`、`records`、`total`、`size`、`current`、`pages`；明细返回上述安全白名单，不返回整个 details。包括 Mock、本地未发送和未知用量，用于解释排行排除规则。

聚合与明细只读取 `MODEL_CALL_RECORDED`、schemaVersion=1、actor_user_id IS NULL 的事实，不连接用户身份表。SQL 全部在 `ModelCallAnalyticsMapper.xml`，参数采用 `#{}`；固定分组通过 XML choose，不拼接外部列名。SQL 校验 JSON 数值后转换，损坏元数据不导致 SQL CAST 失败。分页沿用 MyBatis-Plus 的 PostgreSQL 拦截器及精确计数语句。

五类 Token 在数据库中仍为数字；API 合计与明细采用十进制字符串/null，避免超过 long 的合计或超过 JavaScript 2^53 的数字丢失精度。界面表格使用 BigInt 精确展示，ECharts 柱图仅用于相对规模比较。

## 4. 页面与兼容性

`AdminAnalyticsPage` 增加独立 `AdminModelCallPanel`，采用已有主题变量、Element Plus 中文控件和 ECharts。管理员主动点击「查询对账」加载；不复用上方账号筛选。周期、范围、来源、Top N、重置、失败重试与明细分页均独立处理。编辑草稿不会改变当前翻页的生效条件；新查询与卸载时防止过期响应覆盖。

原有账号活跃、使用排行、日/小时/月操作数、直接增强/Plan 次数与已有有界运行期计数口径保持原样。匿名事件不参与这些账号统计。历史通用事件无法反推调用数或 Token，不补造上线前的对账数据。充值功能继续延期。

## 5. 验证

本轮验证记录见 [模型调用对账回归（2026-10-11）](../testing/模型调用对账回归-2026-10-11.md)。浏览器展示采用模拟 API；PostgreSQL 聚合采用真实本机库与事务回滚。没有调用付费模型，不能把这些验证描述为供应商账单已完成实测对齐或生产容量验收。
