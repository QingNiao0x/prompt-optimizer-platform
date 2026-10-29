# 51后续评测最小入口方案（未冻结）

状态：有限51入口与实际reader离线适配已完成，11项新检查及三题受控HTTP构造通过，见`adapter-verification.json`；0真实调用、未正式冻结或启动。未修改产品、旧49／50行为、管理员目录、默认31或已闭原件，不打开封存题。须根任务独立复核源码／测试并明确批准后才能开始。

建议先用原生Flash51验证协议兼容的目标故障：沿用已闭Flash50三题Q01/U02/U03完整public DTO，连modelId也保持Flash，仅作业内部version改51。草稿`development-bundle.json`，正常prepare＋writer共6次，阶段最多12（含失败／有界修复），不质量重试。保持8192／0.2／JSON／disabled／streamfalse；不要求协议修复同时创造正文收益。

| 未来入口 | 已完成的最小适配；等待正式阶段授权 |
| --- | --- |
| freezer版本 | 有限枚举及generationPayloadVersions增加51→51；不加任意版本或别名 |
| 运输guard | 49/50严格registry校验的有限版本增加51；仍与job.strategyVersion一致、完整输入不变、最新真实prepare实际reader.resolve、原sourceId／引文／题答与权限目录逐字绑定。不得用合成registry顶替实时prepare |
| 服务激活／闭批 | GENERATION51启用同一guard；固定schema零上游事件允许51且必须属于本冻结版本；预算、失败计数、闭批不可重开均沿现有机制 |
| actual51离线回放 | 使用该版本实际writer兼容reader→normalize；准备、来源、真实QA、安全、原稿和组装覆盖照旧。不把旧strict-reader工具的拒绝误算51产品失败，也不因忽略无权威空标量就放过来源／正文引用或未知结构错误 |
| 代码与参数 | 新阶段必须新source/class快照；模型与全部参数冻结，不与仍在修改的target混跑。49/50冻结常量、旧响应回放保持原意 |

若根任务选Pro51，应另建有限的原生Pro51 profile和独立stage，复用已闭Pro50真实DTO、同模型／参数做50→51兼容对照；不能把一次性Pro50 profile改为通配，也不能偷改它的已闭协议或混入新内容指导。该选项尚未准备可执行bundle；是否运行及其最多12次由根任务在工程与Pro50语义结论后决定。当前Flash草稿不等于已批准Flash／Pro双矩阵，最多24不自动获得执行授权。

普通C01/C02/V01/V02/V03与新实际Plan的36/40方案及工具可继续保留，须先过当前开发语义门；没有新增必要性就不重复UI或作品。新题不得挪用旧49封存题，应在新候选冻结后另行独立出题。

当前总额143/500，22批闭锁，评测Java及9014均已停；见`../interim-ledger-143.json`。Pro50三题200只是实际交付，正文语义与作品收益尚未据此判通过。没有执行finalize或采用。
