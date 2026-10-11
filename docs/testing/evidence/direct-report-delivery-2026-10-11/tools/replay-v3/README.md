# v3 固定响应离线重放

此次复制 candidate-v2/main 的 379 个文件到 candidate-v3/main，仅覆盖当前工作区 DraftDeliveryContract.java。源 SHA 检查确认与 v2 恰好一处差异：DraftDeliveryContract.java，SHA-256 为 CAA8BDAAE39CE5E50A0AF8EBDD2D23E9E6CC97CB7FAEFD5E197149A2B673FBD0。独立 POM 的源码、资源和输出都指向 candidate-v3，不使用共享 target。

`run-1/summary.json` 为结果索引。6 个增强流程消费 v1 的 8 个归档真实响应（两模型科研题各包含首次 JSON 契约失败和一次修复），所有请求 JSON 树与 v1 相等；最终 optimizedPrompt、sections、appliedConstraints、ambiguities 全部相等。比较不依赖 JSON 对象键序，没有删除或规范化请求字段。

生产编译、工具编译、重放执行退出码均为 0。`candidate-v3/compile.log`、`compile-1.log`、`run-1.log` 保留首次记录，本轮无失败重跑。

工具从 v2 工具复制，修改 v3 路径及 source 校验比较基准，仍使用 candidate-flash-v1 和 candidate-pro-v1 的真实 trace。工具不读取 application.yml 或环境凭据，不访问数据库与历史；内存路由为假凭据和 offline.invalid。request factory 始终抛 NETWORK_FORBIDDEN，拦截器不调用 execution。响应按归档 content、actualModel、finishReason、usage、httpStatus 重建，不宣称未保存的信封字段逐字相同。

这是固定 v1 响应的生产组件重放，网络与付费调用数为 0，不是 v3 新真实模型调用，不产生新下游作品，不能作为 HTTP 身份、历史或作品质量验收。日志继承生产 mock=false，只表示使用生产 Provider，不能视作本轮付费调用。

核心命令（PowerShell，仓库根目录执行；已有目录不覆盖）：

```powershell
$env:JAVA_HOME = 'E:/Delelop/Java/jdk-21'
$env:MAVEN_OPTS = '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp -Xmx768m -XX:ActiveProcessorCount=2'
& 'E:/Delelop/apache-maven-3.9.14/bin/mvn.cmd' -o -f 'tmp/direct-report-20261011/candidate-v3/pom.xml' -DskipTests compile '-Dstyle.color=never' -q

$taskRoot = Join-Path (Get-Location) 'tmp/direct-report-20261011'
$taskCp = (Join-Path $taskRoot 'candidate-v3/target/classes') + ';' + (Get-Content -LiteralPath 'tmp/backend-local-managed/2026-10-08T11-36-35-858Z-31d87dcc/runtime-classpath.txt' -Raw).Trim()
$taskBuild = Join-Path $taskRoot 'replay-v3/tool-classes-1'
New-Item -ItemType Directory -Path $taskBuild
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' '-J-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -encoding UTF-8 --release 21 -cp $taskCp -d $taskBuild (Join-Path $taskRoot 'replay-v3/OfflineDeliveryReplay.java')
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Xms32m' '-Xmx384m' '-XX:ActiveProcessorCount=2' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp ($taskBuild + ';' + $taskCp) com.promptoptimizer.enhancement.service.impl.OfflineDeliveryReplay (Join-Path $taskRoot 'candidate-v3/target/classes') (Join-Path $taskRoot 'replay-v3/run-1')
```

`summary.json` 保存生产类、工具类、工具源、源清单、输入、每个原始 trace、原始结果文件的 SHA-256。每个重放请求及最终结果独立保存；本任务只新增 candidate-v3 和 replay-v3，未改 v1/v2、原工具、生产源码或 docs。
