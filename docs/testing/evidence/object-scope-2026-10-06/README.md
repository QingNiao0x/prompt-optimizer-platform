# 资料对象保真与最小整理：证据导航

本目录保存2026-10-06本轮最终候选10及候选05首次失败的合成业务证据。仅含为验收编写的需求、材料、模型作品、评审和代码快照，不含登录凭据、真实个人材料或模型密钥。`source/`是验收时的源码快照，不属于运行期Java模块。

## 1. 先看结论

- [完整报告](../../资料对象保真与最小整理真实作品验收-2026-10-06.md)解释改动、测试边界和待修复项。
- [真实作品汇总](./checks/actual-work-summary.json)：192份实际作品、120组同信息比较，28胜、22负、55平、15组双方失败，`releaseApproval=false`。
- [真实接口汇总](./checks/api-summary.json)：两个DeepSeek优化模型、两轮、6题、72个增强／计划阶段。零问题计划可由平台短路，不等于72次上游推理。
- [后端回归](./checks/backend-regression.json)：134类、1382项，1374通过、8条件跳过。
- [最终评测工具检查](./checks/evaluation-tool-tests-final.log)：32项通过；早期工具记录另存，不能代替最终结果。
- [前端请求与状态检查](./checks/object-web-regression06.log)和[类型检查](./checks/object-web-typecheck06.log)：20项通过及类型检查通过，没有改前端公共交互。
- [长尾定位](./checks/long-tail.json)：一次约59秒请求主要来自上游截断后的受控重试，不能归因于索引CPU。

这轮不能宣布全面上线通过。48份合成Java作品全部编译及功能通过，检查42,768组输入；16份增强新闻均保留MVP，但9份偏短；24份增强或同答案直接增强的法律作品中，9份仍有明确版本格误绑定。没有把专业正确性、任意工程代码通过或统计显著优势写成已经保证。

## 2. 输入、接口与源码

- [corpus.json](./corpus.json)：冻结的6个中长需求，涉及医院、新闻、科研、法律及两种代码工具；记录实际字符数，不以题号中的M/L代替长度。
- `api/r1/`、`api/r2/`：实际请求采集、Plan问题、实际提交答案、最终增强结果及冻结清单。携带资料的题实际走过后端上下文准备，未模拟接口；不代表完整浏览器文件夹扫描已经验收。
- `source/`：31个共享源码快照，相对路径以`services/api/src/main/java/com/promptoptimizer/`为基准，与两轮清单匹配。清单汇总哈希为`8b5fcc0a12586d50330205c8ee9d55c5914b160f05ed2efd49eb53d4d6faff74`，不是Git提交哈希。
- [最终评测工具哈希](./checks/evaluation-tool-hashes-final.json)：包括追加的资料表格诊断。其适用范围有限，属于首轮作品失败后的补充检查，对所有组使用同一规则。

## 3. 逐份作品与匿名评审

| 优化器与轮次 | 实际作品 | 原始评审调用 | 最终同信息报告 |
| --- | --- | --- | --- |
| Flash第一轮 | [works/r1-flash](./works/r1-flash/jobs.json) | [reviews/r1-flash](./reviews/r1-flash/jobs.json) | [报告](./works/r1-flash/final-report.md) |
| Pro第一轮 | [works/r1-pro](./works/r1-pro/jobs.json) | [reviews/r1-pro](./reviews/r1-pro/jobs.json) | [报告](./works/r1-pro/final-report.md) |
| Flash第二轮 | [works/r2-flash](./works/r2-flash/jobs.json) | [reviews/r2-flash](./reviews/r2-flash/jobs.json) | [报告](./works/r2-flash/final-report.md) |
| Pro第二轮 | [works/r2-pro](./works/r2-pro/jobs.json) | [reviews/r2-pro](./reviews/r2-pro/jobs.json) | [报告](./works/r2-pro/final-report.md) |

每个`works/`目录包含原始输入、执行任务、实际响应、匿名ID到作品哈希的映射和最终报告。`reviews/`保存真实模型评审响应、首次导入、必要的语法／字面引文修复和实际断言校正结果。未重写作品、调整原始评分或用成功重跑覆盖首次失败。

Pro第一轮的两份导入缺少数组闭合符；Flash第二轮的一处标题引文多一个`#`。`-v2.json`保留修复元数据，原记录同目录保存。这些是工具导入修复，不是新增模型评审，也不算专业人工认证。

最终Plan执行输入**只有平台的可复制正文**。原始及直接增强的同信息组追加一次真正提交的Plan答案；答案不额外补到Plan正文。原始及直接无答案的初始信息轨单独配对。所有组固定材料和执行模型，不给模型发送隐藏答案卡。

## 4. 实际硬要求优先于AI评分

- `checks/java-*.json`：作品哈希、Java21编译及纯内存组合断言。只支持这轮两种合成工具，不接入生产任务、文件、网络或密钥。
- `checks/news-*.json`：正文按汉字计数，标题不计；原600–800范围与MVP必要事实同时检查。
- `checks/source-*.json`：仅检查本轮未绑定维修条款被放入具体A/B版本表格。`NO_DEFINITE_TABLE_BINDING_FOUND`是未检出这一确定错误，不是完整法律语义通过。
- `checks/source-*-first-diagnostic.json`：保留首版工具将关系问题误判为版本标签的记录；最终统计采用修正后的检查。
- `reviews/*/object-scope-grounded-reviews*.json`：确定的代码、篇幅或版本格错误追加为硬失败，保留AI原评分及引文；PASS不会删除AI仍发现的问题。

本轮AI评分曾漏掉实际硬失败，因此不能只读评分就认定作品通过。6题重复采集的120组配对互相关联，且是AI辅助评审；28胜22负不证明普遍或统计显著增益。

## 5. 首次失败保留与复核方式

`first-failures/candidate05/`保存首次接口结果、输入、检查及两份真实错误Java作品：退款`amount>1000 → MANUAL`分支遗漏。首版Java白名单误判`class`的工具拒绝记录也保留，修正后才执行真实断言。

候选05使用旧评测协议，在Plan正文后又追加了答案，不能与候选10合并计算胜率。候选09及其他中间记录继续保留在本地`tmp/`，本目录没有把它们冒充最终通过结果。

复核先核对[artifact-hashes.json](./artifact-hashes.json)的文件字节与SHA-256，再依据`jobs.json`、`execution/*.json`、`blind-mapping.json`追溯输入、作品和评分。重新运行工具时，在新的`tmp/`运行目录恢复所需结构，使用仓库的Java、新闻、资料表格检查和`ground-actual-work-reviews.mjs`；旧归档只读，不覆盖。不需要再次调用模型即可检查本轮断言与配对；新模型调用必须另建批次并固定候选、参数和输入。

仍待完成：下游版本格误绑定、开放表达复合重复、部分低价值追问与推荐范围、正文精简、新闻长度、独立未见样例与完整上传流程。专业人员核验按用户安排放在上线后；这些基础业务质量门槛不能因此写成已经通过。
