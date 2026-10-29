# 52实际有效要求谱系：确认与未知两状态

本次仅只读核对闭批原件，新增模型调用0；未改源码、harness、旧原件或默认31。两条52生成仍判失败，不重采、不开新题或作品、不冻结53。独立核对当前29个正式阶段均已闭批、无开放阶段，累计177/500：GENERATION115、PLAN12、EXECUTION50。

**结论：两条路径在实际PREPARE响应中，已经把原始严格日期条件重新列入当前执行要求；writer随后照此覆盖，没有纠正，最终组装没有再改写正文。** 明确确认路径把矛盾合进同一条`r1`；未知路径把严格条件`r0`与未知状态`r1`并列。它们与51原件的具体记录形态不同，不能只复述“两个冲突要求并列”。

## 输入与传递事实

原始请求本身同时包含“只能填写当日之前的日期”“不能超过今日”和“今天是否允许填写由我确认，不要代选”。要定位的是：实际题答进入后，这个边界如何被处理，而不是把原始旧句说成平台凭空添加。

两条PREPARE均收到完整原请求、4份实际准备材料和各自真实题答：

| 路径 | 实际题答`actualAnswers[0]` | 真实准备／writer |
| --- | --- | --- |
| 明确确认 | 题：调查日期是否允许填写今天（today）？答：**允许填写今天：日期等于 today 时校验通过，晚于 today 时返回错误。** | [001](E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/traces/001.json) → [002](E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/traces/002.json) |
| 仍然未知 | 题：调查日期等于今天时，是否允许填写？答：**今天是否允许尚未决定** | [003](E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/traces/003.json) → [004](E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/traces/004.json) |

两份`taskSources`均真实包含`{id:"actualAnswers.0",kind:"BOUND_PLAN_ANSWER"}`；字段为`id/kind`，不是`sourceId`。各自prepare与writer的原文及实际题答逐字相同。两份prepare采用同一52非空题答system，SHA256为`0921875ecb4c9cfedc125db923f1de381166578162140b3a78aac0a4df22fd34`。没有答案丢失或错走空题答分支的证据。

## 明确确认：001已把新旧规则合成同一当前要求

1. `001.response.choices[0].message.content`中的`actions[0]`为`timing:"CURRENT"`、`condition:""`，正文同时写“调查日期只能填写当日之前且不能超过今日；按用户确认，日期等于 today 时校验通过，晚于 today 时返回错误”。**这是本条在真实确认之后，旧严格条件首次仍被重申为当前动作的位置。**
2. 同一准备响应的`requirements[1]`全文是：

   > 调查日期只能填写当日之前且不能超过今日；日期等于 today 时校验通过，晚于 today 时返回错误。

   `dimensions=[CONDITION,USER_CONFIRMATION]`；同时引用`rawPrompt`的“只能填写当日之前的日期，同时又不能超过今日。”和`actualAnswers.0`的允许今日答复。引用真实，但条目自身逻辑冲突。`response.description`已正确表达“等于today通过”，也没有消除actions和该条目的旧规则。
3. 002的`preparedRequirements[1].reqId="r1"`。逐项比较确认：服务端仅为001的requirements按顺序加`r0…r3`，内容不变；`taskScope`亦与001的actions/response完全相同。并非服务端另造了一个旧条件。
4. 002的`coverage[reqId=r1].bodyQuotes[0]`为：

   > 调查日期只能填写当日之前且不能超过今日；按已确认的规则，日期等于 today 时校验通过，晚于 today 时返回错误。

   `requirementChanges=[]`、`openDecisions=[]`。writer的短`goal`虽已写成正确的等于今日通过，正文仍按r1复述矛盾，未使用稀疏补正通道。

## 仍然未知：003承认未知，却让严格范围继续独立生效

1. 003的`actions[0]`仍为`CURRENT`、条件为空，同时写“只能填写当日之前的日期，同时不能超过今日；今日是否允许填写保持未决，不代选”。**首次冲突同样已在准备输出中出现。**
2. `requirements[0]`登记严格规则，随后成为004的`r0`：

   > 调查时间限制适用于新增和导入两个入口，只能填写当日之前的日期，同时不能超过今日

   `dimensions=[GOAL,OBJECT]`，仅引用原文。`requirements[1]`成为`r1`：

   > 今日是否允许填写保持未决，由用户确认，不得代选

   它真实引用原始保留决定权句及`actualAnswers.0`的“今天是否允许尚未决定”。因此不是未知回答被丢掉或被直接写成“不允许今日”，而是**未知状态与已经排除今日的严格条件同时留在待覆盖清单里**。这里的`USER_CONFIRMATION`是维度标签，本身不是“已确认”布尔状态。
3. 004完整继承003的scope和要求，仅增加`r0…r2`；`coverage.r0`引用：

   > 为新增和导入两个入口增加调查时间限制：调查日期只能填写当日之前的日期，同时不能超过今日。

   `coverage.r1`则引用“今天（等于当日）是否允许填写尚未决定，请保持未决，不要自行选择或默认放行/拦截”。`requirementChanges=[]`，并保留`openDecisions`中的今日边界。
4. 正文随后又要求“把‘当日之前且不超过今日’的日期范围校验纳入共用校验逻辑”。这实际要求落实排除今日的范围，与“不默认放行/拦截”相冲突。增加“把这一判断留成可明确切换的位置”没有消除该矛盾。不能把保留了一条openDecision解释为未知状态已经得到正确处理。

## writer到组装：没有新的信息变化

两条最终`body.data.optimizedPrompt`均与各自writer的`optimizedPrompt`逐字相等：

| 路径 | writer及组装正文相同SHA256 | 实际结果 |
| --- | --- | --- |
| 明确确认 | `b382c1ba37f2b3be0a8207f0ce30a968257edd0dbcef95a62d2fdcc5efed6438` | [P02_CONFIRMED_V52_GEN](E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/results/P02_CONFIRMED_V52_GEN.json) |
| 仍然未知 | `df583d24bc02baec729242e2704b47963378f3a50bd22554fdc8e9b883d23ea7` | [P02_UNKNOWN_V52_GEN](E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/results/P02_UNKNOWN_V52_GEN.json) |

四份trace和两份result均已与原闭批`closure.json.files`逐一核对SHA256，完全一致。实际完成阶段均为SCOPE_ANALYSIS、BODY_GENERATION，没有跳过正文或组装后追加旧句。

责任边界是：PREPARE未把“来源真实”转成一致的有效要求；服务端如实编号传递；writer虽仍看到完整原文/真实题答且有`requirementChanges`入口，却未纠正准备错误；来源、引文及覆盖可通过，仍不能证明这些句子逻辑相容。现有程序并未强制逐字复制旧句，首次错误位置也不等于唯一根因。52只增加有效要求指导，没有在这两份真实结果中解决该共同机制，故停止这一指导假设的继续采样，不以更换版本号或同义表述重试。
