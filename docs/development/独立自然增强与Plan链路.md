# 独立自然增强与 Plan 链路

本次按用户确认的方案，把关闭“结构化提示词”的生成与 Plan 提问迁入独立链路。默认结果是完整自然正文；开启结构化时继续使用原生成器，并按背景、任务、输出要求、约束条件、验收标准五个模块返回。平台仍只生成优化提示词，不执行原始任务。

本文件说明当前实现。真实模型效果与开放门禁见[开发验证记录](../testing/独立自然增强与Plan链路开发验证-2026-10-19.md)，历史质量失败不会因接口改造自动关闭。

## 1. 模式与职责

| 入口 / 偏好 | 生成策略 | 返回及兼容 |
| --- | --- | --- |
| V2 默认或 COMPACT | NaturalEnhancementService → NaturalPromptProvider，独立 system 指导、输入、解析和正文校验 | resultKind=TEXT，optimizedPrompt 为唯一正文，不返回 sections |
| V2 STRUCTURED | 原 EnhancementOrchestrator → 五模块适配 | resultKind=SECTIONS，保留独立待确认事项和示例要求 |
| V1 优化接口 | 原实现 | 不切换现有调用方的生成逻辑 |
| V2 Plan | 独立自然 Plan 策略 | 两种最终排版共用真实题答；排版偏好不构成事实或授权 |

自然链不调用 PromptEnhancementProvider、旧任务模板、EffectiveTaskView、PlanDecisionIntegrator、OptimizationResultAssembler 或旧业务规则追加器。复用的能力限于服务端身份、模型目录与路由、并发和审计、敏感扫描、上下文分析与过滤、Plan 所有权和期限以及历史持久化。TaskIntentResolver 只判断固定工程安全条款是否适用，不参与新正文的模板或内容生成。

独立生成协议见 [NaturalPromptInstructions](../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java)，应用入口见 [NaturalEnhancementService](../../services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementService.java)。

## 2. 自然正文生成

输入保留原始提示词、当前授权上下文、此前仍允许使用的 Plan 资料、服务端确认过的完整题答、适用对话、示例偏好与固定权限边界。资料内的命令不能改写平台指令或新增授权。

模型依据任务动作、交付物、材料用途和独立条件，从 APE、BROKE、COAST、TAG、RISE、TRACE、ERA、CARE、ROSES、ICIO、CRISPE、RACE 中选择一个组织参考；无法可靠匹配时回退 APE。该选择是生成诊断信息，不是对用户的专业身份判定，也不要求输出框架标题或填满字段。服务端不再使用职业关键词映射决定十二种模板。

正文先表达目标与交付，必要规则放在适用动作附近。充分需求保留有效表达，模糊需求补充有依据的步骤、输入用途、条件和可检查交付要求；不强制角色、字数、段数或通用验收长段。缺材料只限制依赖它的部分，不能把原定报告、代码、方案改为补材料问卷。

模型仅返回 optimizedPrompt 和 framework。服务端检查必要字段、框架枚举和敏感值，并补回缺失的固定平台条款及用户追加权限；不会补入旧任务规则或构造四个假段落。固定规则的重复校验仅检查同一完整原句，不能证明自然改写的语义等价或全面覆盖原始需求。

示例开关仍作为生成输入；原文明示的禁例、严格格式和事实边界优先。其任意语义变体的正确性依赖后续真实评测，不能用请求字段已传入替代质量验收。

## 3. Plan 全流程

1. 有资料时，浏览器先调用 V2 上下文准备接口，获得仅属于当前服务端身份和原始需求的短期引用。
2. 独立 Plan 指导只询问会实质改变目标、事实、业务选择、授权或交付质量的重要未知。用户已经委托的设计不退回逐项填答；充分需求允许零问题。
3. 问题全部通过协议校验后才注册会话。容量为最多 64 题，界面逐题展示；超限失败，不截取前几题假称完整。选项最多 12 个，推荐必须有依据。
4. 最终生成核对所有者、TTL、需求指纹、上下文引用和模型。题号集合必须完整且无重复；题干由服务端恢复。禁用自由回答的选择题还核对实际选项值，多选保留选项内部的分号。
5. 二次检索使用真实题答；首次资料不被第二次结果覆盖。首次快照按当前权限重新过滤；存在新排除文件时，不复用无法逐字段归属的技术栈、目录等聚合摘要，仅保留仍允许的原片段和用户描述。
6. 原始题答进入独立生成协议，未经旧确认融合器重写。历史使用同一份服务端题干保存，避免客户端伪题干进入再次增强。
7. 零问题计划仍有服务端绑定。生成成功、历史保存成功之后才记录 Plan 完成；失败不保存伪结果，不把推荐当成用户答案。

已有 Plan 重选、自由回答、过期恢复、迟到响应隔离和结构化重新生成继续由原工作台状态管理。历史回答恢复为带来源的既有用户信息，不伪装成仍有效的短期 Plan。

## 4. V2 结果与历史契约

| 路径 | 用途 |
| --- | --- |
| POST /api/v2/context/planning | 安全上下文准备；V1 路径保留 |
| POST /api/v2/optimizations/plan | 独立 Plan 提问 |
| POST /api/v2/optimizations | 按显式模式分发生成，成功后保存一次 |
| GET /api/v2/optimization-history | 同时查询新旧历史，先在 SQL 中过滤范围再分页 |
| GET /api/v2/optimization-history/{id} | 原样读取已保存正文 |
| POST /api/v2/optimization-history/{id}/re-optimize | 按已保存的形态、链路和版本分发 |
| PATCH /api/v2/optimization-history/{id}/document | 保存完整自然正文及编辑版本，不调用模型 |
| DELETE /api/v2/optimization-history/{id} | 同租户、工作区范围内逻辑删除 |

上下文普通分析、模型目录、认证与 Plan 事件仍沿用已有 V1 接口；不因为版本号不同复制这些业务模块。

PromptDocument 的 resultKind 区分 TEXT 和 SECTIONS，generationPipeline 区分 NATURAL 和 LEGACY，chainVersion 记录生成契约。当前支持 NATURAL/1、LEGACY/1 和 LEGACY/2；LEGACY/2 表示五模块投影，旧记录缺省 LEGACY/1。旧 COMPACT 不能被排版字段误判为新自然链。未知版本再次增强返回 409 / RESULT_VERSION_UNSUPPORTED。

TEXT 的 wire 响应不包含 sections；前端为兼容集合操作将其归一为空集合，不构造假段落。旧 V1 历史列表只查询 SECTIONS，直接读取新正文会返回稳定版本冲突；V2 列表和详情同时支持两类结果。上线时前后端需同批更新，不能让 V2 前端连接未升级的后端。

历史正文不重新渲染。再次增强保留已保存链路；自然记录还保留服务端公开模型标识。已有优化记录不批量迁移、不重写、不补造框架信息。

## 5. 编辑、复制与撤销

自然模式用一个完整文本编辑器。显示和复制均取实际 optimizedPrompt，不把正文拆成四要素。清空、保存失败和接口版本冲突均保留本地草稿；有未保存修改时阻止再次增强或离开。晚到保存响应不能覆盖另一个任务或结果。

客户端仅依据服务端 constraintGroups 恢复缺失的平台 / 追加权限原句；服务端再次核对敏感值、固定边界、当前租户和工作区、未删除状态与 expectedRevision。SQL 比较版本后才更新，避免其他页面的旧版本覆盖新稿。第一次编辑前的 generatedPrompt 保留不变，editRevision 递增，editedAt 使用 UTC。

同一自然记录的编辑撤销也保存到历史；不同生成结果之间的撤销只切换查看。历史关闭时编辑仍可在本地使用，recordId 为空，不伪造保存成功或虚构历史 ID。旧分节编辑保持原行为，未借本次改造承诺分节编辑也持久化。

## 6. 模型、审计与迁移

OpenAiCompatibleNaturalPromptProvider 通过既有受并发保护的 RestClient 调用平台模型目录选定的服务端路由；密钥不进入新模型业务输入或浏览器。保留当前路由的请求容量、Token 参数与 JSON 能力配置，不新增模型或供应商配置。

每次实际尝试都采集审计和用量，包括解析失败。协议或必要保护校验失败最多补一次，共最多两次；鉴权、网络、超时和限流不自动重试。注册 Plan、保存历史等副作用位于校验回调之后，修复不会重复写业务结果。

MockNaturalPromptProvider 只用于离线连通性验证，明确返回 Mock 提示，不能证明实质增强或专业质量。真实调用协议和解析可通过受控 HTTP 测试验证；Mock 不冒充真实模型。

数据库只新增 [V16 前向注释迁移](../../services/api/src/main/resources/db/migration/V16__natural_prompt_document_metadata.sql)，准确说明 JSON 形态、版本和编辑字段，不变更 V1—V15，不修改旧记录，不增加新的业务表。持久化值仍用 Mapper XML 参数绑定；删除继续使用 deleted_at。

## 7. 当前验证边界

受影响的 Java、前端、浏览器和本机数据库验证分别见[开发验证记录](../testing/独立自然增强与Plan链路开发验证-2026-10-19.md)。受控浏览器用例验证编辑、复制和 Plan 交互；真实 PostgreSQL + MVC 验证控制器、安全链、业务和持久化，但不是浏览器连接真实模型的完整质量验收。

本次未发起真实模型、下游作品或 AI 评审请求。此前候选额度 420 次已用满且闭批，累计 490=70+420，正式上限 500/80/420；基线剩余不能挪为候选。新版本仍需在获得新增数值预算后，冻结题目、模型、参数和调用分配，验证同输入两态的格式、任务清晰度、交付要求、紧凑度、Plan 必要性与保真，并检查真实作品。

旧链的事实、确认、凭据、重复和专业案例失败保留为风险证据。独立协议、五模块格式、构建或测试通过均不能替代真实效果、专业验收与 G1—G8 门禁。

