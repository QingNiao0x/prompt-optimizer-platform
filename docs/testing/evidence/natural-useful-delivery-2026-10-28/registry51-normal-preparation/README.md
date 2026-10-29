# Flash51 normal：最小零调用准备

本目录仅准备已有正常变体及Plan路径，不是实际UI、语义、作品或采用通过。没有启动服务/浏览器、冻结阶段、读取新封存题、修改产品/target/旧工具/闭批原件。Pro50 normal草稿保留未运行，不继续扩大模型矩阵。

## 输入与预算

`generation-bundle.json`复用原C01/C02/V01/V02/V03五份Flash DTO，**包括modelId在内逐字段完全相同**；只改变受控job版本/ID为51。无modelSelectionProfile或experimentProfile，沿用已有Flash51受控入口。`input-equivalence.json`保留源hash及五份相同DTO的hash。

三个阶段分别冻结：正常GEN20（五题）、新实际Plan8（P01/P02）、实际确认后GEN8（P01/P02），总36、常规预计18；失败和协议修复计数，零质量重试，不带旧40计划的4次机动额度。普通五题ID为C01/C02/V01/V02/V03_V51_GEN；Plan为P01/P02_V51_PLAN；后续生成为P01/P02_V51_CONFIRMED_GEN。当前Plan仅原文/路径模板，无伪造contextId、planId、问题或回答。

## 复用及实际范围

- 直接import旧Flash工具的picker、Worker/IndexedDB核对、召回检查、JSON/hash与禁止覆盖函数；不改旧50入口或源码。新薄入口只明确51的metadata guard、实际Flash选项点击和有限UI流程，不新增评测平台。
- `node live-browser.mjs <future-stage> C01_V51_GEN`默认预检；只有未来root/infra授权、LOGIN_READY和独占后才加`--execute`。实际点击公开Flash选项，完整79文件picker/Worker/IndexedDB，严格匹配冻结DTO、@目标召回、单业务POST、实际51/mockfalse/BODY、原生clipboard及按recordId精确历史。不是日常DEFAULT验证。
- 其余C02/V01/V02/V03是同原资料API验证，不声称五题都完整走UI。
- `node live-browser.mjs <still-open-normal-stage> P01_CAPTURE|P02_CAPTURE`默认预检；未来授权`--capture-context`才实际选择Flash、全79索引并执行确定性context准备，最后Plan固定409，零模型。按实际prepare保存原引用和有效期；不重开闭批服务或静默重绑。
- 真实Plan及确认后生成由infra使用新捕获另行冻结，属于API/应用所有权链路，未覆盖完整UI选项点击。P01必须实际零问题；P02必须实际询问重要未知并取得明确操作者选择。问题是否真正重要仍由原计划语义门判断，构造器不以“存在一道题”证明其必要。

```text
node build-plan-completion.mjs P01|P02 <fresh-capture-dir> <actual-51-Plan-result> <explicit-selection> <same-Flash-input-base> <new-output-job>
```

构造器严格读取真实51结果，不把metadata改成50绕过旧校验。复用既有字段形状和原语义规则，小型51构造函数只处理本次真实Plan/来源/题答：原问题和所有回答、原文、材料、权限、context引用及TTL保持；不自动选推荐或固定选项ID，不造actualAnswers。输出仍为51，附明确serviceProtocolOnly provenance；应用所有权与最终保存策略仍需实际服务验证。

Plan选择记录沿原`kind: EXPLICIT_OPERATOR_SELECTION`、`planId`、ISO `selectedAt`、`answers`，每题含真实`questionId/question/optionIds/customAnswer/answer`。P01真实零问题显式确认继续时answers为空。base须同原文、同Flash、同对话和上下文描述，至少保留全部真实准备材料；不能删context修协议。

原调用边界、原业务语义判据和严重错误门不变。51三题的新稿无阻断不证明旧语义风险已消除；正常变体过门后才决定新题和同信息作品，默认31保持。
