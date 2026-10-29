# 官方原生 Pro／Flash 单变量对照：准备结论

可实施，产品代码不需要改。只改变本次请求选择的模型：Flash50 → Pro50；原始提示词、完整材料、来源、strategy50、system50、8192／0.2／JSON／thinking disabled／stream false和全部保护不变。它比较整条准备＋正文链的模型选择，不是仅writer能力，也不因三次单样本就证明稳定提升。

当前只准备，尚未正式freeze/start/调用。总额137/500；普通变体、实际作品及新题均未开启，默认31和管理员目录／默认模型／用户偏好不改。

## 映射证据与尚待实测部分

`application.yml:219–223` 已配置官方DeepSeek路由和 `deepseek-flash,deepseek-v4-pro`；公开ID通过 `routeKey:model` 形成，Pro为 `deepseek:deepseek-v4-pro`，上游名为 `deepseek-v4-pro`。不能误用 TokenHub 的 `deepseek-v4-pro-0813`。User／Machine的七项公开路由配置变量未发现覆盖；没有读取受保护.env或输出密钥。

`PlatformModelCatalogService.resolve`只接受当前已启用、路由可用的公开目录项；实际数据库是否仍发布Pro须未来服务正常登录后核验，不能把静态配置当成已验证可用。Provider已有对应Pro的JSON／关闭思考参数；[官方API说明](https://api-docs.deepseek.com/api/create-chat-completion/)也列出两个模型及这些参数。官方端点是[同一个Chat API](https://api-docs.deepseek.com/quick_start/pricing)。没有新增端点或凭据。

正式调用前，`verify-model-selection.mjs --execute-get`只读核对真实身份、`/api/v1/models`，以及临时受保护GET中实际 `catalog.resolve` → 已配置route。不存在、停用或映射不符就停，不创建替代目录项；每次增强前服务端也再次核对。输出只含公开ID、上游名、routeKey、官方endpoint和既有默认状态。AFTER再次核对默认ID未变。当前没有运行这些GET。

## 已准备的最小入口变化

| 位置 | 有限变化 |
| --- | --- |
| 新 `official-model-selection-profile.mjs` | 只准这次Pro、GEN50、Q01/U02/U03三题、12上限；核闭批Flash50输入，原DTO只准modelId变化。拒绝端点／路由／参数覆盖、混用51、材料变化和重复冻同一实验 |
| `freeze-live.mjs` | 新profile派生固定Pro公开名／上游名；原无profile仍Flash，不接受任意模型或endpoint。500总账不改 |
| 临时Java服务 | 继续按协议动态核上游模型；新profile只准固定7个请求字段、JSON、disabled、无effort及8192；复用原registry/source/QA/raw/assembled保护。新增只读目录路由核验，不发模型 |
| `start-live.ps1` | Pro专用复制已闭 `registry50-development/runtime/classes` 和原classpath，按旧manifest逐hash核验；不编产品、不取当前target，不混入后续候选。普通启动行为不变 |
| 新只读核验工具 | 默认预检，显式GET才访问本机；固定账号和一次冻结作业，安全结果wx保存，不POST、不造trace、不打印私有会话 |

基准50源码及system完全冻结，不增加51兼容。旧21批次与500协议保留；旧结果不重判，不把HTTP200或合法JSON当语义通过。Pro返回也须通过实际reader/normalize；截断、超时和协议失败按真实尝试计数，不能改上限后补采。

## 草稿与验证

`development-bundle.json`：SHA256 `5bd4df7ff14e0e2239740d2e7748de1c54b930dc7936f3bc4f4db65570252167`，3作业，常规6次、最多12。输入逐字对照见`input-equivalence.json`。未来stage拟为`model-selection50-development`；现未创建。

新边界自测：Java参数13项、Node profile11项、只读预检21项，共45项；没有扩跑旧矩阵。私有tmp只编临时helper，未启动Spring／服务、未编产品。首次Java自测遗漏静态类所需路径属性，出现NPE；已补测试专用属性后通过，0上游调用，不属于模型失败。旧21闭批安全原件及task协议hash核对见`infrastructure-verification.json`。

等待根任务确认后才能freeze/start。先只读确认真实Pro目录／路由，再三题各执行一次，保留全部原件并闭批停服务；不质量重试。语义按事先原判据核Q01查询主体、U02时间／资源、U03观察／建议／因果状态；正常阶段、新题及作品仍待另行门控。
