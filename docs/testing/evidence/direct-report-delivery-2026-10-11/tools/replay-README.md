# v2 固定响应离线重放

此次仅复制 candidate-v1/main 的 379 个文件到 candidate-v2/main，并以工作区当前 DraftDeliveryContract.java 覆盖同名文件。冻结 SHA 检查确认恰好该文件与 v1 不同。独立 POM 的源码、资源和输出都指向 candidate-v2，不使用共享 target。

`run-1/summary.json` 为结果索引。6 个增强流程消费了 v1 的 8 个响应（两模型科研题各包含首次不符合 JSON 契约的响应及一次修复），所有请求 JSON 树与 v1 相等；最终 optimizedPrompt、sections、appliedConstraints、ambiguities 全部相等。比较不依赖 JSON 对象键序，没有删除或规范化请求中的字段。

编译生产类、编译重放工具、执行离线重放三步退出码均为 0。`candidate-v2/compile.log`、`compile-1.log`、`run-1.log` 保留首次运行记录，本轮没有失败重跑。

工具不读取 application.yml、环境凭据、数据库或历史。路由仅使用内存假凭据与 offline.invalid，request factory 始终抛 NETWORK_FORBIDDEN，拦截器不调用 execution。HTTP 响应按归档的 content、actualModel、finishReason、usage、httpStatus 重建，未归档的响应信封字段不宣称逐字还原。

这是固定 v1 响应的生产链组件重放，不是 v2 的新真实模型调用，不产生新的下游作品，也不证明 HTTP 身份或历史全链路。工具日志继承生产 `mock=false` 字段，只说明使用生产 Provider，不能将日志计数当作本轮付费调用数；本轮网络与付费调用数均为 0。

核心命令（PowerShell，仓库根目录执行；已有目录不覆盖）：

```powershell
$env:JAVA_HOME = 'E:/Delelop/Java/jdk-21'
$env:MAVEN_OPTS = '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp -Xmx768m -XX:ActiveProcessorCount=2'
& 'E:/Delelop/apache-maven-3.9.14/bin/mvn.cmd' -o -f 'tmp/direct-report-20261011/candidate-v2/pom.xml' -DskipTests compile '-Dstyle.color=never' -q

$taskRoot = Join-Path (Get-Location) 'tmp/direct-report-20261011'
$taskCp = (Join-Path $taskRoot 'candidate-v2/target/classes') + ';' + (Get-Content -LiteralPath 'tmp/backend-local-managed/2026-10-08T11-36-35-858Z-31d87dcc/runtime-classpath.txt' -Raw).Trim()
$taskBuild = Join-Path $taskRoot 'replay-v2/tool-classes-1'
New-Item -ItemType Directory -Path $taskBuild
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' '-J-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -encoding UTF-8 --release 21 -cp $taskCp -d $taskBuild (Join-Path $taskRoot 'replay-v2/OfflineDeliveryReplay.java')
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Xms32m' '-Xmx384m' '-XX:ActiveProcessorCount=2' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp ($taskBuild + ';' + $taskCp) com.promptoptimizer.enhancement.service.impl.OfflineDeliveryReplay (Join-Path $taskRoot 'candidate-v2/target/classes') (Join-Path $taskRoot 'replay-v2/run-1')
```

`summary.json` 保存生产类、工具类、工具源码、源清单、输入、每个原始 trace、原始结果文件的 SHA-256。每次重放请求和每个最终结果独立保存；原 v1 证据、原工具、输入、run.ps1 和生产源代码均未被此工具修改。
