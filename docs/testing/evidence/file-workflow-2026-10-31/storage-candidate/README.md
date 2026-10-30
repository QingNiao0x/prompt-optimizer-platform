# 未采用的数字词键实验

基线为 `c9b5eb5e`，候选由本目录 `rejected-v5.patch` 标识。该补丁不属于当前生产代码，只供复核；不得直接在日常浏览器配置中升级实验数据库。

- `production-real-scope.json` 固定真实文件范围与摘要；`production-real-comparison.json` 是同输入 ABBA 两轮结果。
- `full-project-v5.json` 保留整库第一次检索失败及完整进度、阶段耗时、抽样核对结果；其 `passed:false` 没有被补验改写。
- `key-probe.json` 为小规模探针；`project-index-term-keys.spec.ts.txt` 记录实验迁移、碰撞等回归，使用文本扩展名避免被日常测试自动加载。
- 最终生产代码仍是第四版。实验在小范围节省占用、缩短写入，但整库收益未证明；不能据此声称当前已提速。

根文件漏召回在撤回实验后独立修复；验证使用生产第四版、450 个同名干扰文件，不覆盖整库重新扫描。
