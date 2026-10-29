# 原生 Pro50 正常变体：仅零调用准备

依据已闭批模型对照的阶段决定，准备继续验证明确选择原生Pro的50；不宣称Pro整体稳定胜出，不采用50或切换默认31。没有读取未开放的新题、启动浏览器或服务、注册profile、冻结阶段或调用模型。既有工具、闭批原件、产品及共享guard不修改。

## 有限计划与材料

现有五题为C01/C02/V01/V02/V03；从`registry50-normal-preparation/generation-bundle.json`复用完整DTO，只将`modelId`变为`deepseek:deepseek-v4-pro`，strategy50。原文、附件、权限、输出范围等逐字段不变，见`input-equivalence.json`。保留全79文件的项目夹具、同名旧模块和72份噪声，不缩小索引帮助召回。

三个profile名字目前都只是planned，**未在正式freezer/server中注册，不能直接冻结或执行**：

| 未来阶段 | planned profile | 作业 | 最大实际调用 |
| --- | --- | --- | --- |
| 直接增强正常变体 | NATIVE_PRO50_NORMAL_GEN_V1 | C01/C02/V01/V02/V03_V50_PRO_GEN | 20 |
| 新真实Plan | NATIVE_PRO50_PLAN_V1 | P01/P02_V50_PRO_PLAN | 8 |
| 实际Plan后增强 | NATIVE_PRO50_PLAN_COMPLETION_V1 | P01/P02_V50_PRO_CONFIRMED_GEN | 8 |

合计36，常规预计18，失败和协议修复都计数；不保留旧40计划中的4次机动额度，不质量重试。三个阶段须由root批准、infra有限化后分别冻结，不复用已关闭三题模型对照的profile或运行入口。

## 真实路径与诚实范围

- **C01完整UI**：`live-browser.mjs <future-stage> C01_V50_PRO_GEN`默认预检；未来另获授权才`--execute`。正常GET核实公开Pro模型，再真实点击模型下拉中的该公开选项，并只读核验store实际选择；不设置store或改POST metadata假装选择。真实picker→Worker→79文件IndexedDB→@目标召回→冻结DTO全等→单业务POST→50/mockfalse/BODY→原生clipboard→按recordId精确历史UI。只检验受控候选入口，不冒充日常DEFAULT。
- **其他四题**：准备同模型API请求，未声称全UI重测。继续检查禁止新增/测试、充分需求、只阅读等待及明确没有材料等原条件；协议成功不等于语义通过。
- **P01/P02上下文准备UI**：`capture-plan.mjs <still-open-normal-stage> P01|P02`默认预检，未来授权`--capture-context`后，实际选择Pro、全79索引并保存真实拥有者上下文及Plan DTO；最后Plan固定409阻断，零模型，不模拟成功。应在正常阶段结束、临时服务仍开着时捕获，保留真实TTL；不能重开闭批或悄悄换引用。
- **P01/P02真实Plan及后生成**：infra未来用捕获DTO单独冻结Plan。P01必须实际零问题后生成；P02必须实际提出重要未知、由明确操作者回答后生成。`build-plan-completion.mjs`只按本次真实prepare/Plan、明确选择和完整材料构造后续服务DTO，再单独冻结；这是API及所有权链路，不是完整UI选项点击验收。没有实际结果时不造planId、题号或actualAnswers。

P01原文为C01明确“不能超过今天”；P02保留“当日之前/不超过今日”的冲突并将今天是否允许保留给用户。原约定材料和文件范围保持。构造器不按固定选项ID或推荐自动作答，允许真实存在的非推荐选项；原问题、所有实际回答和上下文引用传给应用服务，真实所有权、原文绑定及TTL仍须实际服务验证。

```text
node build-plan-completion.mjs P01|P02 <fresh-capture-directory> <actual-plan-result.json> <explicit-operator-selection.json> <same-input-base.json> <new-output-job.json>
```

选择记录沿原构造器的`kind/planId/selectedAt/answers`，每题含真实`questionId/question/optionIds/customAnswer/answer`。generation base必须同原文、同Pro模型、同对话和上下文描述，并至少保留全部实际准备材料；不能通过删除context修协议，也不能沿用旧已确认Plan。P01显式确认继续时answers为空。所有输出是待冻结服务请求，未执行完整UI最终Plan流程，不代表应用所有权验证已通过。

项目索引、picker和召回检查直接import旧工具的模型无关函数。新Pro guard/原生UI选择/有限CLI局部维护，不修改旧Flash工具，也不以临时改Flash标记来绕过其模型检查。尚未运行的选择器、服务profile或Plan调用均不记PASS。

后续采用仍看语义与实际作品。比较31/50策略作用时，增强模型也须同为Pro；旧Flash31不能作为同模型策略对照。正常变体过门前不打开新题或启动作品。
