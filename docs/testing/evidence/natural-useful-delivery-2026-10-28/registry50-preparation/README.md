# 候选50评测入口准备

这里只准备未来评测入口，不是当前采用、发布或质量通过结论。真实总额仍为130/500，默认31；没有新增正式阶段、启动服务或模型调用。49原件与49封存题不改、不解封；新未见题须另由根任务安排。

## 输入与范围

三题开发包：`../registry50-development-bundle.json`，SHA256 `a0570823edf124d6bf0fd07a58e07f610a675a23accafb95813700d61b812ba6`。Q01/U02/U03均直接复用49完整工作台DTO，`JSON.stringify` UTF-8逐字一致；只改作业版本、编号和机制说明，不增删材料、权限、回答。正常prepare+writer每题2次；阶段最多12，失败和协议修复计数、不做质量重试。

正常包：`../registry50-normal-preparation/generation-bundle.json`，五题C01/C02/V01/V02/V03来自既有真实captures-r2；C01仍完整79索引。只准备GEN20；Plan2题PLAN8、实际Plan回答后GEN8另行冻结，整体最多36，40封顶中未分配4不自动使用。正常变体不是未见题，也不能取消49 U03已确认的语义失败。

参数与49开发保持一致：DeepSeek官方flash、8192／0.2／JSON／thinking disabled／stream false。既有产品默认10000不改；不继承高思考的32K或300秒临时配置。当前不安排作品。

## 四处未来入口差异

| 文件 | 具体差异 |
| --- | --- |
| `../freeze-live.mjs` | 明确枚举加入50；50内部载荷只允许strategyVersion50，不接受其他别名；总额500与阶段冻结边界不变 |
| `../NaturalUsefulDeliveryEvaluationServer.java` | 49/50 GENERATION都调用既有registry guard；Plan仍用原路径；不改请求内容或计数位置 |
| `../NaturalRegistry49CallGuard.java` | guard有限集合49/50，作业与输入版本必须相同；沿最新真实prepare及实际strict reader/resolve核对registry／scope，来源、权限、完整输入、stop要求不放宽；安全事件保存本作业的49/50版本 |
| `../close-stage.mjs` | 安全零上游事件只允许49/50且必须属于当前冻结版本；原件保持逐字复制、零调用计数、闭批不可重开 |

没有修改49已闭runtime副本、原协议或原件；旧入口源码的未来有限版本扩展不追溯改变已闭运行事实。没有执行finalize、改默认或生成采用门。

## 零调用检查边界

`infrastructure-verification.json`记录27项新检查：17项guard版本／strict共享registry检查，10项未来freeze／closure边界；未重跑旧大套件。临时Java helper仅编译到`tmp/registry50-offline-runner`，依赖冻结49 runtime/classes；没有编译产品或修改target。测试中的合成prepare只证明共享schema与外发边界，不能证明真实50准备提取能力。

`boundary-before.json`和新验证报告记录四工具前后hash、16个49闭批文件hash及task-protocol不变。三题完整输入一致性见`input-equivalence.json`，五正常题见`normal-input-equivalence.json`。

仍需等待：50产品owner工程完成、根任务独立源审和新freeze；之后用真实50 Provider分支做0调用构造／normalize核对。只有根任务另行批准，才可正式freeze/start三题；开发语义过门后才能运行正常与新实际Plan。当前合成guard夹具不得作为实时registry输入。

独立工作台／Plan工具由browser owner放在`../registry50-normal-preparation/`：真实目录选择、全索引、DTO、BODY、原生剪贴板及recordId精确历史；Plan构造器只接受新实际上下文、Plan和操作者选择，不能复用过期旧ID。是否完成、接口与自测以该目录说明为准，不能从本文推断已运行。
