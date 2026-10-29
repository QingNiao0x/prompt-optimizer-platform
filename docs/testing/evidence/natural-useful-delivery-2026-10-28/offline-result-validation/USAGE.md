# 自然正文真实结果离线校验

本入口只读取已闭批的 `jobs.json`、`closure.json`、结果和原始trace。Java进程禁用HTTP，通过冻结runtime中的真实产品方法校验，不请求模型、不改候选、不写入闭批目录。

在仓库根目录运行：

```powershell
& 'docs/testing/evidence/natural-useful-delivery-2026-10-28/offline-result-validation/Invoke-NaturalModelResultReplay.ps1' `
  -StagePath 'docs/testing/evidence/natural-useful-delivery-2026-10-28/repair48-replay' `
  -OutputPath 'docs/testing/evidence/natural-useful-delivery-2026-10-28/offline-result-validation/repair48-replay-validation-v2.json'
```

报告不得覆盖；再次执行请指定新的输出文件名。未闭批、原件哈希不符或所选job不在冻结列表时，入口停止，不产生候选通过结论。

后续思考机制阶段同样使用此入口，将 `StagePath` 换为其闭批目录。默认运行全部job；可用 `-JobId 'JOB_A','JOB_B'` 选择已冻结job。默认runtime与依赖路径是 `tmp/natural-useful-delivery-2026-10-28/<阶段目录名>/runtime/classes` 和同阶段的 `dependency-classpath.txt`。如果该独立profile复用已冻结48类副本，可以显式指定：

```powershell
  -RuntimeClasses 'tmp/natural-useful-delivery-2026-10-28/repair48-replay/runtime/classes' `
  -DependencyClasspathFile 'tmp/natural-useful-delivery-2026-10-28/repair48-replay/dependency-classpath.txt'
```

旧单次writer回放先核对闭批原件哈希、实际trace请求与冻结 `approvedModelRequest`、HTTP状态、双方finishReason及结果/trace内容一致性，再从真实 `body.data.content` 运行原产品的：

1. JSON围栏处理、重复键/尾随内容及既有有限闭合兼容解析。
2. 必需字段、`NaturalPromptInput`、来源目录、`resolve`及全部题答来源保护。
3. 框架枚举、正文敏感值检查、用户明确权限的原组装方法。
4. 原稿与组装正文的原句覆盖校验。原稿已拒绝时，组装覆盖仅作为额外诊断运行，不改变首次拒绝。

49/50全产品阶段使用同一入口，版本限定在这两个已实现的registry协议分支；未来版本不会自动接入：

```powershell
& 'docs/testing/evidence/natural-useful-delivery-2026-10-28/offline-result-validation/Invoke-NaturalModelResultReplay.ps1' `
  -StagePath 'docs/testing/evidence/natural-useful-delivery-2026-10-28/registry49-development' `
  -OutputPath 'docs/testing/evidence/natural-useful-delivery-2026-10-28/offline-result-validation/registry49-development-validation.json'
```

50闭批后将示例中的阶段名换为 `registry50-development`，报告改用新的文件名；必须使用同阶段冻结50 runtime。入口根据冻结job中的49/50版本和 `approvedEnhancementInput` 选择同一适配器，核对实际输入版本与冻结job一致，并从已加载产品类选择对应prepare/writer指导。50指导通过固定类名反射读取，避免旧49快照因不存在50类而无法编译；没有对应类或不支持版本时停止，不回退到近似指导。

它按原件顺序回放所有实际prepare/body尝试，核对完整原输入在阶段及重试间未改变，使用实际Provider的严格reader和 `Preparation.resolve` 生成registry，再逐字核对writer携带的 `preparedRequirements`、`taskScope` 及 `registry49.preparedTrace`（50沿用此元数据键）。writer通过实际reader和 `normalize` 得到规范任务记录，随后运行上列来源、全部题答、安全、原稿及组装保护；最终交付正文必须等于实际组装结果。报告保留每次拒绝、服务器编号与更正绑定、输入及原件哈希，不把准备fixture或手写registry当作真实准备响应。

两个适配器都只在自己的 `tmp/.../offline-result-validation/<阶段名>/classes` 编译。旧48快照只编译旧适配器，不要求存在49类；不修改 `target`、冻结runtime、模型原件或阶段执行入口。49额外字段与完整public DTO的产品外发一致性由阶段transport guard负责，本入口核验真实原始输入、来源目录和实际prepare→writer链，并保留public DTO哈希供联合审计。

报告记录原件、冻结输入、实际候选及已加载产品类的SHA256，不导出环境、依赖classpath、认证配置、headers或推理正文。原件没有改写；报告中的组装哈希来自原产品正常组装，不是人工补救。

`VALID_PRODUCT_DELIVERY_PROTOCOL_ONLY` 只表示上述工程保护通过。它不证明目标、事实、时间范围、委托范围或其他要求已完整保留。所有结果都保留 `semanticReviewRequired=true`；真实遗漏、越界和作品收益仍须独立语义审查。HTTP 200、合法引用或脚本退出码0均不能代替该审查。失败、截断、缺失和证据冲突单独记录，不补采，也不改称持平。
