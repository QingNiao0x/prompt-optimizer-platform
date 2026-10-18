# 候选三十四：requests两态受控复验入口

本目录当前为准备材料，提案尚不代表协议已生效。固定原题为“使用requests库爬取网页标题。”，直接增强COMPACT／STRUCTURED，无Plan、附件或示例。最多8次：两请求共享增强3次（含现有协议修复）、原始／两份原生复制正文作品3次、两次独立AI辅助评审。失败全部计数，不做质量重试；分项余额不互借。

1. 负责人冻结`candidate34-source.json`后，运行`pwsh -File <本目录>/start-candidate34.ps1`，默认只编译包装并执行离线scope预检，不启动服务、不预占请求。它生成本轮`expected-inputs.json`与`scope-preflight.json`。
2. 固定评测代码工具的哈希到`evaluation-tools.json`，不把将变更状态的`live-proposal.json`列作代码工具。`python <本目录>/approve-batch.py`默认只读预检；负责人核对后才执行`--apply`，将候选三十三已关闭余8显式重分配至本批。正式399／80／319不变，实际最多381→389；不挪用基线余额。
3. 负责人核对本项目进程与端口后，显式运行`start-candidate34.ps1 -Serve`。脚本遇9001占用直接停止，不杀进程。沿用本机Redis、本机源码及既有5174页面服务，不用Docker。
4. 显式运行`node <本目录>/account-browser.mjs`使用原测试账号和既有candidate9浏览器profile。密码只从`tmp/local-acceptance/credential.dpapi`经Windows SecureString解密到子进程stdout并由Node内存捕获，不写明文文件或命令参数；验证码仍正常获取并人工读答，不创建账号。若已认证则直接核对身份。
5. `node <本目录>/run-live.mjs run-step REQUESTS COMPACT`执行一次真实页面增强，保存请求、响应、原生剪贴板与新历史比对；成功后再执行`run-step REQUESTS STRUCTURED`，实际切换偏好并点击基于原始需求重新增强。也可用`run-case REQUESTS`顺序执行两步。切换本身不能发请求、改旧结果；各操作一次性防重复，文件不覆盖。
6. `node <本目录>/external-calls.mjs prepare-works`只冻结三臂请求。两份成功原生复制正文、历史核对和哈希任一缺失时，写明`SKIPPED_DEPENDENCY`并跳过作品／评审，不重建提示词或补平台遗漏信息。每臂仅一条user消息，复制正文原样发送，仅比较时归一CRLF。
7. 负责人分别执行`pwsh -File <本目录>/run-external.ps1 work REQUESTS RAW`、`... COMPACT`、`... STRUCTURED`。同一正式DeepSeek端点和冻结参数，不自动重试；调用前复核来源／输入哈希并逐调用预占。原始作品是产品比较基线，不重采其他题目。
8. 对保存作品做已有requests代码核验：参考[候选十九独立检查](../me00a-candidate19-live-2026-10-16/independent-code-check.py)和[候选三十一合成凭据回放](../me00a-candidate31-contract-lookup-2026-10-17/replay-saved-code.py)的固定HTML、异常、Token／userinfo／API Key用例。两者是旧原件专用入口，不能直接运行它们冒充新作品验证；新适配只映射实际入口及原样代码，不补写实现，网络必须禁止。实现与自带测试一同编译／执行，合成凭据回显单独记录；没有相同通用函数名不自动判质量失败。
9. 独立报告保存至私有`tmp/me04-candidate34-live-20261018/requests-checks/<臂>/result.json`，以`workSha256`绑定完整作品，注明`sourceRepaired:false`、`externalModelCalls:0`及`mechanicalGate:PASS|FAIL|OPEN`。`node <本目录>/prepare-reviews.mjs prepare-reviews`冻结随机盲标与两个评审请求；未有独立报告保持OPEN，三份作品不齐则跳过评审，不补缺失作品。
10. 显式执行`run-external.ps1 review R1`／`review R2`，再运行`node <本目录>/audit-reviews.mjs`提供的离线审计入口。两位是同供应商Flash／Pro独立AI辅助评审，不称人工专家或跨供应商评审；保留分歧及逐字引文检查，生成时未执行与事后工具验证分开。

若增强3次共享额度耗尽、某请求失败或无合格复制正文，停止其依赖路径；不扩分项、不自动借旧余额、不重试到满意。只执行已满足依赖的冻结范围，未覆盖项保留。所有请求、作品、账户身份与历史详情留在私有`tmp`目录；公共记录只保存合成任务、哈希、计数及必要结论，不复制DPAPI密文或明文凭据。
