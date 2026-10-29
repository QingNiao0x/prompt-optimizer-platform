# 50 普通流程与 Plan 的离线验收工具准备

本目录的工具尚未运行真实浏览器或模型，不是50候选已通过或已采用的证据。仅准备代码、运行离线语法和合成边界自测；未启动服务、冻结阶段、创建账号、编译产品或读取新的封存题。

## 普通工作台

`node live-browser.mjs <future-frozen-stage> C01_V50_GEN`默认仅预检。未来获得root独占授权及infra的LOGIN_READY后，才可另加`--execute`。

- 只使用50候选入口，不检查或切换日常DEFAULT。
- 核对当前正式阶段、未闭批/未尝试状态和完整source-manifest哈希；所有.class必须属于该阶段runtime/classes。没有猜造尚不存在的candidate50源冻结哈希。
- 使用固定合成账号真实登录状态；真实FileSystemDirectoryHandle、用户点击选择目录、Worker及IndexedDB处理完整79文件，包括同名旧目录和72噪声文件。picker选择结果来自合成OPFS，未人工弹出系统目录窗口。
- 实际UI请求与冻结DTO逐字段全等后才允许唯一业务POST；传输不重试。服务端可能执行既有协议修复，其实际调用仍由正式总账计数，不能把单POST写成单模型调用。
- 强制50、mock=false和BODY_GENERATION；观察目标模块召回、原生clipboard及显示内容。
- 根据真实历史列表的recordId找到对应行，然后点击该行查看；再断言实际详情GET就是本次recordId。不会点击第一条相似历史冒充一致。
- 原件保存到本目录`live/<stage>/<job>/`及正式runtime结果区，仅未来获授权执行时写入。不把HTTP200、BODY或复制一致直接当质量收益。

## 新 Plan 准备与服务协议构造

`capture-plan.mjs <future-active-frozen-stage> <capture-id> <raw-prompt-file>`默认预检；另加`--capture-context`才会启动浏览器并执行真实确定性/context/planning。最终Plan POST固定409阻断，不模拟成功，零模型调用。输入参数使用本次原始提示词，完整79文件走真实选择/检索；不写死旧planId、contextId或旧46题答。

未来真实Plan执行由infra新冻结阶段负责。得到实际成功结果后，可离线运行：

```text
node build-plan-completion.mjs --capture-dir <本目录新plan-captures/...> --plan-result <本次实际PLAN-result.json> --selection <操作者明确选择.json> --base-input <同原文及完整材料的generation-input.json> --out <本目录新准备输入.json>
```

选择记录字段为`kind: EXPLICIT_OPERATOR_SELECTION`、真实`planId`、ISO `selectedAt`及`answers`。每项包含真实`questionId/question`、实际选中的`optionIds`、`customAnswer`和明确记录的`answer`。可以选择非推荐选项，不能自动把推荐当选择；记录的answer必须等于对应真实选项含义或明确自填内容。选项按实际Plan顺序整理，与当前PlanQuestionDialog一致。示例字段说明不构成任何真实选择。

构造器严格校验真实prepare/Plan引用、有效期、全部独立题答和材料保留；过期立即失败，不能重绑新引用或补造actualAnswers。generation base必须同原始提示词、模型、对话和上下文描述；至少保留全部准备材料，不以删context绕过协议。所有输出仅为待冻结的服务请求，原件和哈希另记provenance。

这一步验证的是“真实上下文准备＋真实Plan结果＋操作者明确选择→最终服务请求”的构造，**不是完整UI Plan选项点击或服务端最终所有权验收**。选择记录也不是认证凭据；真实所有权、题号、原文绑定和TTL仍须应用服务在实际生成时验证。

`node self-test.mjs`只用标明LOCAL_SELFTEST的内存合成对象，检查过期、旧id、伪选项、漏同文独立题答、资料丢失等边界；这些自测对象不写入真实结果或冻结bundle。
