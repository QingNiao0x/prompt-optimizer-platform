# 53 requirements-only首层机制验证方案（仅零调用设计）

当前199/500。本次没有改source／target／harness，没有捕获新请求、冻结、启动、GET或模型调用，也未读取封存题。53仍在工程检查，以下须待源码owner停更、工程冻结及根任务审核后执行。

执行顺序已由根任务后续决定更新：先做独立的52单句最小澄清机制；其实际失败且阶段闭批后，才考虑本53 R-only首层。若较小改法有效，优先验证它的writer，不因53工程已写就自动运行／采用新协议。本文件的53路径和预算是后置方案，不是现在live授权；两机制的结果及归因不得混合。

## 最小首层路径

1. 只复用52闭批`registry52-plan-completion/traces/001.json`（CONFIRMED）和`003.json`（UNKNOWN）的完整实际NaturalPromptInput；保留全部原文、材料、真实题答及来源，仅将策略版本改53。旧Plan引用已过期，这只是历史完整模型输入重放，不冒称新鲜53 Plan、当前确认或普通入口通过。
2. 通过**实际53 Provider受控HTTP request-factory**捕获首PREPARE请求，并在外发前终止；不手写system或近似payload，不返回合成“正确准备”。核原始数据／taskSources除版本外完全一致；无hint、末尾QA新消息或taskScope。请求应为Flash/.2/8192/JSON/关闭思考，且来自实际requirements-only分支。
3. 捕获原件和实际53源码／classes哈希通过后，使用既有exact-approved EXEC入口分别冻结两请求、总EXEC2。两个状态各一次、逐job独立语义门；第一失败就保留第二未执行，不协议修复、质量重试或改问法补采。
4. 使用同冻结53 runtime的实际strict reader读取响应，经新Preparation.resolve生成Registered；来源／全部QA及用户要求仍须通过产品校验。随后独立判断当前有效要求：确认状态无旧严格条件继续生效，未知状态无代选；原独立限制和任务范围不丢。引用旧句作为证据不等于当前执行旧条件，不能按关键词硬判。

预期首层是正常2／最多2实际调用：199→201；若第一失败则200。这里只检查新准备职责／协议的实际结果，不以字段减少、响应合法或HTTP200声称正文质量提高。

## 两准备都通过后才讨论writer2

必须另获根任务批准。把两份**实际模型prepare原响应**回放给同一53 Provider的受控HTTP工厂，让真实Preparation.resolve分配ID并让Provider构造第二段writer请求；捕获后终止外发。不能用手填registry、人工补正、答案卡或旧52 writer替换字段冒充实际53构造。

writer仍接收完整原文、上下文及真实QA，辅助仅实际Registered.requirements；无taskScope/hint。两份源请求、prepare响应、实际归一化ID和第二请求的谱系先固定，再另冻最多EXEC2，各一次。最后走实际53 writer reader／normalize、用户目标来源检查以及原来源、全部QA、安全、原稿与组装覆盖保护，并独立审正文。累计最多203，不自动开启作品。

源码owner当前给出的接口（尚未冻结，不能提前据此导出payload）：

- `NaturalPromptRequirementsOnlyPreparation`：严格wire只有requirements，`resolve(NaturalPromptInput)`返回Registered；`Registered.requirements()`为既有Entry列表，无scope。
- Provider `requirementsOnlyPreparationReader`读准备；writer仍为`referencedDiagnosticEnhancementReader`。
- `NaturalPromptReferencedEnhancement.normalize(input, metadata, List<Entry>)`处理实际登记条目；随后实际运行`NaturalPromptRequirementsOnlyPreparation.validateUserGoals(task)`，不能在回放时漏掉新增保护或自行加最低GOAL数量门槛。

## 预计最少工具改动

| 入口 | 首层需要的有限改动 |
| --- | --- |
| freezer | 仅显式接受本次53 EXEC机制版本及两固定请求／上限；不借机开放GEN、Plan、默认切换或新模型。 |
| 原受控Provider捕获方式 | 做必要的小参数化：这两个完整历史输入、53、只截首请求；后续若获批再回放真实prepare截writer。现有49捕获器依赖三开发题和合成actions/response，不能直接改版本就称53实际提取。 |
| 只读回放 | 有限53分支使用新strict准备reader→Registered，并按实际新normalize／用户目标保护核验。旧51/52 reader和记录不改；工具误用旧actions/response协议造成的拒绝单列工具不兼容，不算模型失败。 |
| start／invoke／计数／close | 复用已验证入口；root批准后才快照真正53 classes，保持单job、500总额、失败／截断计数及闭批锁定。不新增账户、fixture、通用编排或评审模型。 |

机制若成功，仍须以后另行通过新鲜真实Plan及回答、普通工作台／复制／历史、正常变体与未见题／实际作品，才能考虑采用。51／52失败原判、默认31和全部采用门不变；本轮不解封、不建立新题或扩大矩阵。
