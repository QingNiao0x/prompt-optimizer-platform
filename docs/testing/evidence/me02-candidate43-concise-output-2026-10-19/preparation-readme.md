# 候选四十三真实复验准备

本目录先保存可复用准备工具；实际候选源码冻结、正式批次打开和模型调用尚需由执行代理完成。准备行为不等同验收。

## 固定范围与预算

实际账本465（基线70、候选395），正式上限500／80／420；候选算术余量25。原建议26已撤回，改为最多24：Plan6、增强12、作品4、独立评审2。最大实际489，最大候选419；不扩大正式上限。依据本轮代理自决授权，草案明确重分配candidate42r2余11、candidate42r3余2及candidate41r2-core-acceptance余12中的11，共24；最后1次仍关闭。不是将算术余量自动视为新额度。

四次增强：AI职业/行业原题自然与结构化各一次；交接待办与课程方案选择各一次自然Plan后增强。四份作品：原始提示词、新自然复制正文、新结构化复制正文、用户Qoder参考文本。旧截图只离线核对，不作为原生复制正文发出。留出两题只验证实际Plan和增强链路，本批不生成其下游作品。

所有下游作品第一条消息保持各臂提示词原文，第二条消息为相同 `source-pack.md`。API没有浏览工具。本批证明同资料条件下的回答质量，不声称完成原题无资料的实时全球检索，也不声称统计显著胜率。

## 操作顺序

1. 已运行 `python tmp/eval43/prepare-candidate43.py prepare`：复制本目录小型配置/脚本，复用上批受控工具生成私有 Java/浏览器适配器至 `tmp/me02-candidate43-20261019/runner`，不动账本。
2. 产品受影响测试通过、提交后运行 `python docs/testing/evidence/me02-candidate43-concise-output-2026-10-19/prepare-candidate43.py freeze`。它冻结当前源码及target/classes，保存 `candidate43-source.json`、私有源码/运行时快照、`protocol-amendment-43-draft.json` 与私有 `protocol-draft.json`。已存在快照时拒绝覆盖。
3. 运行本目录 `run-candidate43.ps1 -Component Prepare` 编译私有工具及运行离线正反范围检查，不调用模型。最终检查才对应冻结运行时，准备时仅做了Python/Node/PowerShell语法检查。
4. 执行代理核对草案、正式账本和用户授权后，明确激活上述闭批余额24次重分配：将草案状态从DRAFT改为OPEN，authorizationStatus改为APPROVED_BY_USER；将固定题目复制至 `tmp/me00a-20261012/candidate43-cases.json`；保存最终协议修订。准备工具自身不执行这一步。
5. `run-candidate43.ps1 -Component Backend/Frontend/Browser` 分别启动9001／5174／9325，遇端口占用即拒绝；日常服务不停止。浏览器沿用固定账号、既有profile与DPAPI凭据，验证码从私有live-auth目录正常填写，不创建账号。
6. 两次主题：`-Component Flow -Operation direct -Case AI-USAGE -Format COMPACT` 和STRUCTURED；两次Plan：HANDOVER与COURSE-CHOICE各`-Operation plan`，按真实返回题目准备私有prepared-answers，再`finish`。零题会自动增强；重要问题未问或普通题误问时记录真实失败，不改写服务返回。
7. `-Component External -Operation prepare-works` 冻结四份作品输入；按RAW、COMPACT、STRUCTURED、QODER_REFERENCE各`-Operation work -Case ...`。每次写入call-once与正式预占，失败计数，不质量重试。
8. 独立检查四份作品，保存 `tmp/me02-candidate43-20261019/hard-checks.json`。随后prepare-reviews，再R1/R2各review。评审标签W1自然、W2原始、W3Qoder、W4结构化；工具会隐藏供应商和模式标签，但各结论引文仍独立核对。
9. 关闭批次、保留失败与未覆盖范围、停止本批临时进程并核对日常服务。复制/历史只从实际网页结果核验，源码/工具哈希进入结果记录。

## 复用与安全边界

`prepare-candidate43.py` 对候选四十二工具做带断言的有限适配，未复制新产品链路。基础文件哈希和私有生成工具哈希记录在 `tool-adapter-hashes.json`。保留固定输入、参数、路径白名单、官方端点、实际Plan绑定、按用途/阶段/总额预算、空引用兼容、来源视图校验及单次调用记录。若基础文件发生变化，关键替换缺失即停止。

主题原始历史在固定账号内精确查询为0条；未扩大读取他人身份。不把截图转录冒充模型原始响应，不把测试环境前置失败当成模型调用失败。

## 资料来源

- [Gallup：AI Use at Work Rises](https://www.gallup.com/workplace/699689/ai-use-at-work-rises.aspx)，2025-12-14；使用其2025年美国雇员调查事实。
- [Eurostat：Use of artificial intelligence in enterprises](https://ec.europa.eu/eurostat/statistics-explained/index.php?title=Use_of_artificial_intelligence_in_enterprises)，网页标示2025年12月提取；使用2025年欧盟企业采用及用途事实。
- [Anthropic：Economic Index report: Economic primitives](https://www.anthropic.com/research/anthropic-economic-index-january-2026-report)，2026-01-15；使用2025年11月Claude任务分布事实。

2026-10-19已打开官方正文核对后保存简短中文事实摘记，未复制文章全文。各来源统计期、地区、观察单位不同；不是最新全球穷尽检索。源网页自身的Eurostat来源说明存在2024调查/2025调查措辞不完全一致，本包只使用正文明确标注的2025数值，不推断新的年度定义。
