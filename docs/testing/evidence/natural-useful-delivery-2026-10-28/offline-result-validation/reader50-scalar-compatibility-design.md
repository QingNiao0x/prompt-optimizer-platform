# 冻结50无权威标量诊断：零调用隔离结论

只读取已闭批registry50-development的001准备、002首稿、003唯一修复及冻结50类。没有修改产品、target、冻结runtime、原件或50判定，没有创建或手填registry；准备记录来自实际001经真实strict reader和resolve。全部闭批原件先核对closure哈希，选中三份原件及实际加载类哈希见 `reader50-scalar-diagnosis.json`。

已确认：

1. 002的4个additions（下标0–3）都没有bodyQuotes。真实strict reader读取后，normalize报“原要求引用协议列表缺失、为空条目或超出容量”；挂载受保护24兼容的副本仍拒绝，没有补引文。
2. 003的真实strict reader精确拒绝路径为coverage[0].bodyQuotes_note，异常为UnrecognizedPropertyException，该值是空字符串。
3. 在内存副本中仅移除这一个属性，原strict reader、normalize、完整来源/题答保护、原应用安全/组装和原稿/assembled覆盖全部通过。正文没有改写。
4. 原003不作任何改写，仅复制冻结strict reader的实际owning mapper并添加冻结24 handler，同样通过；新增协议保护层后委托同一24 handler也通过。三种隔离结果的完整规范化对象哈希相同。

因此，第二次拒绝已隔离为单个无权威标量；上述副本通过不追溯改变50的502失败，也不证明正文语义正确。

## 最小新候选建议

观察到的最小实现是仅给新候选的writer使用局部reader副本，准备reader保持严格。指导、wire、resolve/normalize、JSON根解析、调用上限、正文与组装判定均沿50；旧24 handler和49/50 reader不改。不应加入bodyQuotes_note或其他题目键的允许名单，也不关闭FAIL_ON_UNKNOWN_PROPERTIES。

新局部handler先拒绝错位的协议语义字段和服务端字段，再委托原24的有界标量判定。协议保留集合应来自实际49准备/正文/范围/原任务与review DTO的字段，覆盖goal、deliverable、inputReadiness、requirements、requirementChanges、reqId、replacesId等；补充preparedRequirements、taskScope及其他服务端阶段辅助字段。已知位置仍由原严格类型读取和业务判定处理；来源origin/kind、ID、索引、题答/阶段字段在未定义位置仍拒绝。

不能直接裸挂旧24：实际隔离对照显示，在coverage[0]额外放goal标量会被旧24忽略，其旧保留集合并未包括该新协议语义位置；保护层会拒绝。原24针对未知标量的名称长度128、值长度20000、敏感名称/值检查继续复用；未知对象/数组仍拒绝。诊断丢弃后不进入DTO、来源、权限、历史或规范化正文。

本次prototype用真实协议record component名称及明确服务端辅助字段形成保留集合后委托24；它仅是独立工具，未接入产品。合计31项标记为syntheticCopyControl的对照满足预期：错位语义/服务器/跨阶段字段、未知对象/数组、敏感值、超长名称/值、已知字段类型猜换、伪造来源原句、缺少新增合成绑定回答来源、无正文原句及正文敏感值继续拒绝；任意未列名的普通string/boolean/number/null标量可丢弃。合成绑定回答仅是输入副本的拒绝对照，不是本题新增真实回答，也没有生成registry。

## 复验入口与边界

独立Java源为 `NaturalRegistry50ScalarDiagnosis.java`，与同目录 `NaturalModelResultReplay.java` 一起用Java21编到 `tmp/natural-useful-delivery-2026-10-28/reader50-scalar-diagnosis/classes`；classpath只使用registry50-development冻结runtime/classes及其dependency-classpath.txt，不使用target。主类为 `com.promptoptimizer.provider.infrastructure.openai.NaturalRegistry50ScalarDiagnosis`，参数依次为仓库绝对路径、闭批stage绝对路径、001.json、002.json、003.json、新报告绝对路径。报告禁止覆盖，HTTP客户端固定抛错。

这是解析兼容诊断与候选设计；实际模型是否更稳定、正文是否保真、是否采用新候选，均未在本工具中判定。完成后停止修改，等待root决定。
