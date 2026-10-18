# 候选三十五：无平台专用标题的单次真实链路准备

本目录由当前节点复用候选三十四工具准备。**准备不表示已经开批或验证通过。** 正式上限仍为总399／基线80／候选319，核对时实际388＝70＋318。只建议明确重分配三十四关闭余1次，固定原题“使用requests库爬取网页标题。”、COMPACT、示例OFF、无Plan／附件。只资助一次实际上游调用，不资助协议修复、作品、评审或质量重试。

## 冻结与离线检查

负责人先完成生产修改和受影响测试，将完整源码及对应classes只读保存至`tmp/me04-candidate35-20261018/frozen/{source,classes}`；在本目录生成`candidate35-source.json`，使用既有字段`version=candidate35`、`sourceFiles`、`runtimeFiles`、`sourceSnapshot`、`runtimeSnapshot`，逐文件保存SHA256。源码清单同时覆盖前后端及e2e；策略清单包含规则、渲染器、组装器、DTO、历史与编辑归属的实际修改。不能将旧三十四classes冒充新候选。

仓库根目录依次执行：

```powershell
pwsh -NoProfile -File docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/start-candidate35.ps1
node --check docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/run-live.mjs
node --check docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/account-browser.mjs
```

第一条默认只编译包装、导出最终运行所用的`expected-inputs.json`，并写`scope-preflight.json`；不启动Spring、不发模型请求、不预占。准备阶段曾使用三十四r1旧冻结classes对工具进行20项作用域检查，仅证明工具可编译及护栏行为，结果在私有`tmp/me04-candidate35-tools-prepare-20261018`，**不是三十五验证**。最终冻结后必须重新执行以上入口，以新classes生成期望taskView和rewriteStrategy。

负责人将所有实际执行工具逐文件哈希写入`evaluation-tools.json`，格式沿用`{"tools":[{"path":"文件名","sha256":"..."}]}`。该清单至少包含本目录Java／MJS／PS1／PY及冻结后导出的输入文件；`live-proposal.json`是会改变状态的提案，不列为代码工具。固定源码和工具后再运行只读检查：

```powershell
python docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/approve-batch.py
```

## 明确开批后的单次运行

以下均为负责人在最终冻结和分配记录后显式执行的入口，准备任务没有执行它们：

```powershell
python docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/approve-batch.py --apply
pwsh -NoProfile -File docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/start-candidate35.ps1 -Serve
node docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/account-browser.mjs
node docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/run-live.mjs run-step REQUESTS COMPACT
```

`--apply`登记修订28，保存旧协议和已关闭来源，正式上限不变。服务9001、前端5174及浏览器9325须先核对项目归属；9001被占用时脚本直接停止，不杀其他服务。启动方法沿用三十三`start-live-services.ps1`的本机Vite5174配置，但需负责人为本目录适配服务归属和日志位置；不能直接用旧脚本混写旧节点。密钥只从本机已声明环境读取，账号继续复用`tmp/local-acceptance/account.json`和受保护DPAPI，不创建任何身份。

`Candidate35EvaluationServer`在每次上游请求前校验官方HTTPS端点、模型、温度／输出预算、实际原题／模式／taskView／rewriteStrategy、批次用途及总／阶段上限，并先预占。第一次响应无效后，既有Provider若尝试修复，第二次会在真实发送前因预算不足停止；不承诺完成三次协议修复。浏览器只允许一个相同范围的提交，原件、平台响应、真实剪贴板和历史保存在私有`tmp/me04-candidate35-live-20261018`；`check.json`公开部分仅包含哈希和断言。

新正文要求无旧平台标题、存在服务端`constraintGroups`，历史与结果的正文／模式／sections／歧义／appliedConstraints／constraintGroups逐项一致。读取剪贴板只在比较时归一CRLF，保存原生字节。失败保留请求、原件及草稿截图，结束调用，不重试。此单次只验真实生成→复制→历史；编辑、再次增强来源与Plan的多组合主要由本轮定向单测及受控浏览器覆盖，不冒充新模型作品。

## 保留的失败与后续范围

详见`quality-evidence-audit.json`。三十四自然正文冗余、示例OFF仍加入示例要求、两增强作品合成凭据回显及自然作品缺可执行测试没有被本目录关闭。移除标题的收益属于阅读与来源一致性；没有新作品不能证明下游安全改进。若要原始／新正文作品对照、两位AI评审或多轮模型诊断，需要先明确数值扩容并冻结分配；不能借基线余额或覆盖旧失败。

## 382／383原始响应的父／新冻结组装对照

`Candidate35Replay.java`只读取既有合成fixture和声明的34-r1／35冻结清单，逐文件核对运行哈希，并核对实际Assembler类来自相应冻结classes。两份原始模型JSON及各自哈希保留不变；382缺省presentation仅沿用Provider已有兼容，最终COMPACT／STRUCTURED通过公开组装入口显式传递。此工具不启动Spring、不读身份、不调用模型、不保存数据库历史。

准备阶段已经用34-r1完成一次父版本回放，结果在`tmp/me04-candidate35-replay-20261018/parent/summary.json`：382正文1168码点、383正文1150码点，旧标题各1次，五条固定完整规则各1次，旧结果没有`constraintGroups`。该数字是固定组装夹具的正文长度，不是下游作品篇幅或新模型结果。旧输出保留不覆盖。

35最终冻结后，负责人在仓库根目录运行：

```powershell
$replayRunner='tmp/me04-candidate35-replay-20261018/runner-candidate'
New-Item -ItemType Directory -Path $replayRunner -Force | Out-Null
$replayDependencyFile=Get-ChildItem -LiteralPath tmp/backend-local-managed -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$replayDependencies=[IO.File]::ReadAllText($replayDependencyFile.FullName).Trim()
$replayClasses=(Resolve-Path 'tmp/me04-candidate35-20261018/frozen/classes').Path
$replayClassPath=$replayClasses+';'+$replayDependencies
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' -encoding UTF-8 -cp $replayClassPath -d $replayRunner docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/Candidate35Replay.java
if($LASTEXITCODE -ne 0){throw 'Replay helper compilation failed'}
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp ($replayRunner+';'+$replayClassPath) Candidate35Replay docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/candidate35-source.json tmp/me04-candidate35-replay-20261018/candidate
if($LASTEXITCODE -ne 0){throw 'Frozen candidate replay failed'}
python docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/compare-replay.py --before tmp/me04-candidate35-replay-20261018/parent/summary.json --after tmp/me04-candidate35-replay-20261018/candidate/summary.json
```

`compare-replay.py`再校验两次原始响应和保存正文的实际哈希，生成本目录`replay-comparison.json`，分别记录正文码点／汉字数、旧标题、五条完整原文次数、扁平规则、来源分组、歧义和其他段落。只移除旧标题行后父正文须等于新正文才标记纯呈现差异；如果不等，保留两份原件并返回失败供归因，不自动修正文或换判据。父版本缺少来源元数据仍保持null，新结果则必须等于服务端输入分组。结果不会关闭已有示例、凭据回显或缺测试的作品门禁。
