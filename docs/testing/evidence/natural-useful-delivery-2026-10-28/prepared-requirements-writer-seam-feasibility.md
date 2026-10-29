# 原有两次调用中的要求识别与正文分工：可行性调查

本文依据本轮已冻结48及当前源码阅读结果，只提出独立机制方案。本文未修改产品、冻结题目或调用模型；当前总额仍124/500，默认31不变。高思考六次实验不构成本方案的成功依据；root已在匿名审查后停止思考强度假设，不据此自动改试low。

## 1. 结论与真实边界

**正常路径可以保持原有两次调用。** 把第1次现有范围准备扩展为“动作/回应边界＋原有要求及出处识别”，第2次保留完整原始输入，但不再让writer重抄整份原要求、来源和分类；由服务端给已核验条目分配reqId，writer生成正文及这些reqId对应的实际正文引文，再确定性转回当前内部任务记录。

这能检验“同时写正文并维护重复证据表是否造成负担”，不能先保证正文会更好。准备响应会变大，writer响应会变小，整体token、耗时、最终有效率和实际补全收益都须测量。准备阶段也可能漏项或错解，错误不能因提前产生就变成权威。

“两次”指正常的Provider范围准备与正文各一次。当前每阶段最多两次协议尝试；本方案不增加分析、裁判或反思调用，不新增质量重试，不声称所有失败请求严格只有两次。ContextAnalyzer已有的可选摘要及Plan另行计量，不能用这句话掩盖其调用。

## 2. 当前代码已经做了什么

| 位置 | 当前事实 | 本方案要改变的职责 |
| --- | --- | --- |
| [NaturalTaskScope](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskScope.java:16) | 仅actions/response；核对动作及回应出处。resolveAdvisory不以CURRENT是否出现关停生成 | 在新DTO中组合旧scope，不给旧record追加必填字段；同一次响应另识别requirements |
| [范围准备指导](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptScopedInstructions.java:27) | 当前明确“不寻找补全项，不填写检查表”，只输出actions/response | 新版本独立准备指导负责原有要求识别；不回改28—48指导 |
| [Provider.enhance](../../../../services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java:99) | 45—48沿范围准备→正文；正文仍解析完整taskUnderstanding | 只在新版本替换第1次返回结构及第2次响应适配，不叠加第3阶段 |
| [NaturalTaskUnderstanding](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskUnderstanding.java:83) | 校验各要求原句，来源类别由服务端回填；模型声明不建立事实或授权 | 复用来源核验；writer不再重复生成未变化条目的text/dimensions/evidence |
| [NaturalTaskReview](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskReview.java:54) | 核对原始需求/全部真实题答来源、覆盖下标和实际正文引文；不证明语义完整 | 继续使用同一内部记录与校验，reqId仅在适配器内转成现有下标 |
| [阶段DTO](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptPhasePayloads.java:42) | 21—24已把来源及bodyQuotes就地绑定，服务端生成下标；writer仍返回原要求全文 | 可借用确定性转换方式，不能声称已经实现本次前后阶段分工 |
| [NaturalEnhancementService](../../../../services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementService.java:112) | 同一完整NaturalPromptInput交Provider，原稿及组装后各做正文覆盖；最后形成PromptDocument | 不绕过此入口，不用拼回原文或准备记录补出“覆盖成功” |

[NaturalPromptInput](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInput.java:15)已不可变地携带rawPrompt、context、actualAnswers、conversation、mandatoryRules及planningContext。[NaturalTaskSource](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskSource.java:29)已从这些原始输入建立来源目录，准备结果不能加入这个目录冒充新事实。actualAnswers的来源包含题干和真实回答，出处命中不能证明回答语义完整，仍须单独检查。

## 3. 建议的最小新DTO与接入点

以下是实现边界草案，字段不进入用户复制正文；不是修改已冻结的旧响应协议。

### 3.1 第1次：范围＋原要求

```text
PreparedResponse {
  actions, response,                         // 复用NaturalTaskScope形状
  requirements: [{text, dimensions, evidence:[{sourceId, quote}]}]
}
```

requirements复用现有Requirement语义及容量，按实际存在的目标、对象、格式、篇幅、条件和用户确认识别，不强行填满六类。只登记原有要求，资料或助手建议不单独建立本次命令；来源类别由服务端确定。此阶段不填bodyQuotes、Coverage、additions或Plan问题，不能提前宣称正文覆盖。

服务端核对原句、来源、敏感值、容量及原始需求/全部实际题答来源集合后，为当前请求的条目赋r0、r1……。这个reqId不是sourceId，不跨请求充当证据。保留原准备响应及规范化条目；scope仍按advisory处理，不能重启31的EXPLICIT门控。

### 3.2 第2次：正文＋覆盖＋必要稀疏补正

```text
WriterResponse {
  optimizedPrompt, framework,
  goal, deliverable, inputReadiness,         // 保持47在writer判断充分程度；两个范围短字段可纠正早期误读
  coverage: [{reqId, bodyQuotes}],
  requirementChanges: [{
    replacesId: null或某个既有reqId,
    requirement: {text, dimensions, evidence:[{sourceId, quote}]},
    bodyQuotes
  }],
  additions: [{text, gap, benefit, bodyQuotes}],
  openDecisions: [{issue, impact, kind}]
}
```

通常requirementChanges为空；writer只回原条目的reqId及真实正文锚点。新增或纠正仅在发现准备遗漏/误解时出现，以完整原输入为依据，重新走同一来源校验，不能引用preparedRequirements。无纯删除操作，也不允许模型自行发行新reqId。

被纠正条目用更正后的要求及其实际正文锚点替换，未变条目不得遗漏。replacesId重复、未知ID、重复覆盖或无正文引文均拒绝；新增ID由服务端生成。若准备误把“阅读后等待”解释成报告，writer可回原用户来源纠正，不能被迫照写报告。纠正引用合法仍不等于语义一定正确，不能因为有correction字段就信任它。

goal/deliverable保留为两个短字段，是为了避免直接bindScope把早期错误固化；不要求writer重建原要求来源表。若实现另选范围补正DTO，应保持相同能力，但不要再造一整套状态表。

正文相关additions/openDecisions不宜为声称“writer只有两个字段”而静默删除：前者保留实际补充的缺口/作用/位置记录，后者保留未决状态及Plan推荐边界。它们随最终正文稀疏返回，不重新列出整份原始要求。若要进一步移走或取消这些记录，应另立假设，不能与本次职责移交混做。

writer输入仍是**完整NaturalPromptInput＋preparedRequirements辅助字段**，准备列表不能替代原始提示词、项目上下文、对话或真实题答。补充、未知和动作判断仍以完整来源为准。framework沿47处理，不顺带做框架消融或内容指导改写。

### 3.3 规范化后复用原保护

服务端将准备要求、稀疏更正、新增要求及writer实际引文合成现有NaturalTaskUnderstanding/NaturalTaskReview：

1. 来源原句仍与原input.taskSources核验，类别由服务端回填；全部实际题答仍独立登记，不靠准备摘要宣称“已覆盖”。
2. reqId转下标由服务端完成；模型不再维护重复的下标表。
3. 未更正的准备要求、最终更正/新增要求及实际additions都必须有真实正文引文。不能拿来源引文代替正文引文。
4. 原稿和最终组装正文仍分别validateEnhancement；权限原句、凭据扫描、Plan归属、历史保存顺序不变。
5. 不把修正后的原文程序性拼入正文救场；失败保留为失败，不返回原文冒充增强。

仅复用旧bindScope强制灌入准备目标、仅对准备列表做覆盖，或由程序把实际答案登记为“已经写进正文”，都不满足此方案。

## 4. 与已做实验的实质区别

| 对照 | 已做了什么 | 为什么不能当作本方案证据 |
| --- | --- | --- |
| [BODY_ONLY](./protocol-ablation/protocol.json) | 1次精确46 writer重放，删taskUnderstanding，只回正文/framework；仍保留原scope与完整输入 | 删除了覆盖记录，没有把已校验原要求移到准备阶段，也未走完整原稿/组装保护；Q01仍以来源/口径为主。不能靠换名再做一次删除协议实验 |
| [旧15](../../../../services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptPreparationInstructions.java:14) | 先生成completionSuggestions，随后明确要求writer按正文重新填写完整taskUnderstanding | 准备记录是候选补充建议，原要求证据仍由writer重抄。它可证明已有两阶段传输和准备校验入口，不能证明本次职责移交有效 |
| [21—24阶段DTO](../../自然增强阶段协议解耦与分层验收-2026-10-22.md) | 分开增强/Plan协议、就地绑定来源和正文、服务端形成下标 | 已减少跨阶段无效字段，但没有把原要求提取从writer移走；报告也未证明整体质量提升 |
| 本方案 | 将原有第1次scope调用用于提取原要求；writer消费已核验reqId且只为其返回正文锚点；仍有完整原文及稀疏补正 | 真正改变了职责和重复证据生成，仍需单独证伪；不是再加一句“专心写正文” |

相比“新增一个任务分析调用”更小；相比“删掉所有检查表”保留保护。若只想修某个无效引文，可继续用现有有界修复，代价更小，但此前48重放已显示不能据HTTP200确认修好，此处不把它当同一目标的已验证替代。

## 5. Plan与调用次数

建议第一版只替换enhance的准备/正文契约；Plan问题生成完全沿48，不改变提问、推荐或会话注册逻辑。Plan确认后的optimize自然会接收服务端绑定的actualAnswers，重新在第1次准备中识别当前有效要求，不复用回答之前的旧清单覆盖真实确认。

| 路径 | 正常Provider调用数 | 本方案 |
| --- | ---: | --- |
| 直接自然增强 | scope＋body＝2 | 原第1次改为scope＋requirements，第2次写body，仍2 |
| 生成Plan | 既有scope＋Plan＝2 | 保持原状 |
| 实际回答后生成 | 新scope＋body＝2 | 新准备收到全部实际题答，仍2；不再补问 |

不要先跑prepareScope再调用旧15准备：那会变成3次并混入补全策略。不要新增自动评审模型、降低来源/安全保护或提高思考强度来掩盖协议问题。

## 6. 最小修改面与离线入口

- 新49准备/Writer DTO及确定性适配器；不改旧NaturalTaskScope形状、不放宽旧字段或旧JSON解析。
- 新49独立准备指导和writer协议说明。正文业务指导以47为基准逐字复用，仅替换输入/输出职责说明；49不顺带继承48修复机制。
- Provider增加49本地reader、enhance分支及preparedRequirements辅助输入；版本注册保持显式，旧31—48响应与指导回放不变。
- NaturalEnhancementService、历史存储、租户身份、数据库、前端项目索引及结构化链路保持现有入口；规范化Draft继续走原校验。
- 新准备DTO与writer引用需要受控HTTP测试及离线回放适配；同一次请求内保存准备原件和规范化registry，不能只保存writer正文后再补造准备依据。

现有[离线结果校验入口](./offline-result-validation/Invoke-NaturalModelResultReplay.ps1)可复用真实来源、安全及组装前后保护，但不能不改适配就宣称能检查新DTO：新响应还需读取对应真实准备记录、规范化reqId映射及稀疏更正，再调用产品的实际新解析入口。HTTP factory继续禁用，禁止回放时暗发修复调用。

受控HTTP测试捕获的三题若使用合成准备响应，只能证明产品请求构造/协议行为，不能冒称真实新准备模型已经完整抽取要求。实际机制验证须明确是否走真实scope＋writer；只重放固定registry的writer实验，其结论只能覆盖该registry条件下的正文阶段。

## 7. 实施前须保留的风险与判据

先离线验证：旧31—48请求/解析回放不变；49只产生原两次正常调用；准备范围误判不关闭正文；来源伪造、助手建议升级、未知/重复reqId、漏实际题答、无正文引文、组装后丢引用、敏感值和跨身份Plan仍拒绝。正常变体至少含充分需求、阅读后等待、明确无资料、材料仅执行时可得、Plan未知回答及真实确认覆盖旧未决。

对准备遗漏和错误必须各有一个完整原文可纠正的用例，防止registry变成唯一依据；同时验证不能借补正偷偷删除用户硬要求。无需为每种职业、日期或具体题目新增分支。

离线和受控捕获完成后，才冻结小规模真实机制阶段。所有尝试包括协议修复计入现有500，失败原件保留；先检查合法交付、原要求/真实确认和新旧独立条件，再检查正文是否更集中于工作内容、补充是否解决实际执行问题。字段更少、引文通过、正文更长、HTTP200或自报检查成功都不算内容提升。

至少分别报告：准备提取遗漏/误解、writer漏用/错改、平台适配或组装误拒、下游作品违约；不能把首个出错阶段当唯一根因。预期好处若未出现就停止该机制，不换近义指导重复采样。

小样本最多支持是否继续验证。采用仍需冻结后新题及相同信息实际作品：错误更少、无严重意图/事实/权限退步、用户补改减少。当前开发题已见，不得重新包装为独立留出；不以新协议更短提前宣布质量提升。
