# P02 真实确认后旧条件并列的信息谱系

结论：首次在“已看到真实回答”的任务理解中重新把旧条件当作当前动作，是准备阶段原件 `registry51-plan-completion/traces/003.json`。旧条件随后成为服务端 `r0`，确认后的新条件成为 `r1`；writer没有使用补正／替换记录，而是分别给两项提供正文引文。最终组装逐字保留writer正文，没有额外插入旧句。

本报告仅只读原件和现有机制，新增模型调用0；当前169/500（GEN111、PLAN8、EXEC50），默认31。未改源码、工具、闭批原件，未开作品／新题。

## 逐步证据

| 步骤 | 实际原件与字段 | 观察 |
| --- | --- | --- |
| 原始要求 | [完成阶段jobs](./registry51-plan-completion/jobs.json)，P02的`approvedEnhancementInput.rawPrompt` | 原文同时写“只能填写当日之前”“不能超过今日”，并明确“今天是否允许填写由我确认，不要代选”。这里是须经Plan处理的原始表述，不是收到回答后仍应同时执行的两项确定规则。 |
| 真实问题与选择 | [实际Plan](./registry51-plan/results/P02_V51_PLAN.json)，`body.data.questions[0]`；[选择记录](./registry51-normal-preparation/operator-selections/P02.json) | q1：“调查日期是否允许填写今天（等于 today 的日期）？”实际选择`allow_today`，`recommended=false`；答案为“今日允许填写：校验条件为调查日期不晚于 today（value <= today），今天通过、未来日期报错”。这是预授权合成夹具操作者对实际选项的明确选择，没有另造用户决定。 |
| 回答进入准备输入 | [准备原件003](./registry51-plan-completion/traces/003.json)，解析`request.messages[1].content`内的JSON | 原始`rawPrompt`完整保留，`actualAnswers[0]`包含真实q1、题干及上述答案；来源目录中的`actualAnswers.0`为服务端题答来源。不存在回答没有传到模型的问题。 |
| 首次错误的当前动作 | 同一003，解析`response.choices[0].message.content`后的`actions[0]` | 旧“调查日期只能填写当日之前的日期，同时不能超过今日”被登记为`timing=CURRENT`、`condition=""`，只引用`rawPrompt`。与此同时`actions[1]`把“用户已确认今日允许、value <= today”登记为`CONDITIONAL`，条件文本仍混有“由用户确认、不代选”和“用户已确认”。 |
| 准备要求列表 | 同一003的`requirements[0]`、`requirements[1]` | 第0项仍是旧“当日之前”规则，维度`GOAL/OBJECT`，仅引用原始提示词；第1项是“已确认今日允许、value <= today”，维度`CONDITION/USER_CONFIRMATION`，引用原始保留决定句及`actualAnswers.0`的真实回答。没有标识第0项已经被新确认更新。 |
| 服务端编号与交接 | [writer原件004](./registry51-plan-completion/traces/004.json)，解析其请求JSON的`preparedRequirements`及`taskScope` | 准备第0项得到`r0`，第1项得到`r1`；两项内容、维度及引文原样交给writer。`registry49.preparedTrace="003.json"`，不是拿另一份准备或遗漏回答。 |
| writer如何覆盖 | 同一004响应的`coverage[0]`、`coverage[1]`、`requirementChanges` | `r0`引用第一句“调查日期只能填写当日之前的日期，同时不能超过今日”；`r1`引用随后“今日允许填写…value <= today”。`requirementChanges=[]`，未发生替换；`openDecisions=[]`、`inputReadiness=SUFFICIENT`。writer的`goal`也并列旧条件和新确认，问题不只出现在排版。 |
| 最终组装 | [最终结果](./registry51-plan-completion/results/P02_V51_CONFIRMED_GEN.json)的`body.data.optimizedPrompt` | 与004的`optimizedPrompt`逐字一致。旧规则不是组装器、复制或历史追加出来的；本报告未重新调用下游执行者。 |

## 两个要求为何都继续生效于协议

“有效”在这里指两项都进入了必须覆盖的原要求清单；DTO没有名为`active`的状态字段。`NaturalPromptRequirementRegistry.Preparation.resolve`在来源与全部真实题答校验后按顺序赋予`r0…`，不负责推断两条自然语言是否相互替代。

`NaturalPromptReferencedEnhancement.normalize`会处理writer明确返回的`requirementChanges`；没有替换时，每个准备条目都必须有对应coverage，并恢复进canonical要求记录。本例changes为空，所以`r0`与`r1`都被保留。来源和正文引文确实存在，只证明可追溯，不能证明同时执行这些句子没有矛盾。

因此，实际链路是：**原始未决表述与新确认均已到达 → 准备阶段仍将旧条件列为当前要求 → 编号保留两项 → writer分别覆盖、没有补正 → 组装原样交付冲突。** 这不是Plan漏答或工具伪造引用；也不能仅凭本例断言程序强制writer逐字复制旧句。它具备补正通道，但本次未被使用，准备与writer都没有把来源留存和当前适用要求分开。

机制定位：`NaturalPromptRequirementRegistry.java:20`（准备校验及编号）、`NaturalPromptReferencedEnhancement.java:54`（替换映射与逐项恢复）。这里只说明共同机制，没有提出特定日期题的补丁。
