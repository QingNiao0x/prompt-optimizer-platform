# 阶段响应协议复用可行性调查

日期：2026-10-28。范围：只读检查当前代码和已关闭评测原件；本调查零模型调用，没有修改产品、冻结指导、历史结果或默认版本。结论不构成候选采用或发布批准。

## 结论与处置

既有阶段 DTO 可以作为未来候选的输出适配机制，确定性转换为当前内部任务理解与覆盖记录；不能直接恢复23/24整套指导，也不能据此承诺正文或实际作品质量提高。

本轮不实施阶段 DTO 迁移。此前删除任务记录的正文消融仍强调统计口径，不能把同一假设换成阶段协议包装再次采样。后续若另有充分证据，只可检验有限的协议可靠性假设，并与正文收益分开报告。

## 已核实的当前接入点

| 位置 | 当前行为与可复用边界 |
| --- | --- |
| `services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptPhasePayloads.java` | `CoveredRequirement`与`CoveredAddition`就地携带`bodyQuotes`；`EnhancementTask.toUnderstanding`按实际条目位置生成内部Coverage，继续调用`resolve`；`Enhancement.toDraft`继续校验真实正文。它没有自动替模型补来源、答案或正文引文。`requireVersion`目前只接受阶段协议支持的版本。 |
| `services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptStageInstructions.java` | 阶段协议减少独立覆盖数组和阶段不适用字段；其现有正文业务指导截取自冻结第四版。23相对22移除框架参考；24相对21仅改变读取端附加标量诊断兼容，不能混为同一个变量。 |
| `services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java` | 47/48先进入RoutingRepair分支，使用旧`taskUnderstanding`读取逻辑；仅将版本加入StageInstructions.supports不能切换这个提前返回的分支。`readEnhancementPhase`可复用，但需要与正文实际输出结构、阶段记录及调用计量一致。 |
| `services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptEffectiveRequestInstructions.java` | 47保留完整来源和全部真实题答，只在相同对象、属性和条件内更新当前有效要求。未来输出适配不能恢复已被真实确认更新的历史条件。 |
| `services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptBoundedRepairInstructions.java` | 48首稿与Plan逐字复用47；只改变既有一次正文协议修复的数据和指导，不增加模型阶段。 |
| `services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptRepairContext.java` | 当前诊断固定读取`/taskUnderstanding`与`review/*Coverage`。若只改JSON封套而不改诊断，阶段响应会被错误报告为缺任务记录。未来应定位真实原始阶段字段，而非制造通过校验的伪规范化记录。无效候选仍只能作为不可信user数据，不能成为来源或授权。 |
| `services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementService.java` | 应保持现有`resolve`、完整实际题答来源检查、原稿及组装后正文覆盖检查、权限保护和最终保存路径。没有修改应用层接口、前端或数据库的必要。 |

## 历史已证明的内容

- `docs/testing/evidence/natural-phase-protocol-2026-10-22/results-summary.md`：4与21各10个增强及Plan作业，首轮均8/10、最终均9/10；21实际返回总token为25,579，4为28,941，约少11.6%。这是本批成本信号，未提升最终有效率，未证明稳定语义收益。
- 同目录`replay-result.json`：21额外`task.task`、`task.kind`字符串造成误拒；仅隔离诊断后，原正文、来源及覆盖可通过。原21失败仍保留。
- `docs/testing/evidence/natural-phase-compatibility-2026-10-22/results-summary.md`：24仅在读取端处理无权威标量诊断，三份旧失败原件回放通过；非法语义字段、错类型、跨阶段字段及敏感值仍受保护。定向工程修复与整体正文质量结论分开。
- `services/api/src/test/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPhaseProtocolTest.java`已有来源伪造、引文伪造、漏独立同文答案、缺正文引用、跨阶段字段及一次协议修复的边界测试；本调查没有重新执行这些测试，不将历史测试结果写成新增验证。

## 已出现的反证及不能重复提出的强假设

- `docs/testing/evidence/natural-phase-compatibility-2026-10-22/retry-diagnosis.json`：24工具题仍将正文“非空行”误引为“非空空行”；另一次Plan后生成漏引同文但独立的`actualAnswers.1/.2`。移除显式索引不能消除逐字引文及完整来源关联的全部负担。
- `docs/testing/evidence/natural-useful-delivery-2026-10-28/protocol-ablation/results/ABQ01_BODY_ONLY.json`及该批`protocol.json`：单次删除任务记录的46正文消融，结果仍以来源、统计口径与局限组织全球AI使用查询。这不足以证明协议完全没有影响，但已不支持“只要删协议就会解决正文重点”的强结论；不能通过重命名候选重采同一假设。
- `docs/testing/自然增强阶段协议解耦与分层验收-2026-10-22.md`和两批摘要均明确：协议通过不保证信息状态、Plan问题必要性或实际作品质量。23去框架指导没有稳定优势，不应捎带全局删除框架。

## 仍未验证的有限假设

在47当前有效要求指导和48定点修复下，把正文引文就地绑定要求，是否减少错关联、阶段不适用字段及必要修复成本，同时不损害有用补全和实际作品质量。历史第四版协议对照没有检验这个组合，但这仍是协议可靠性假设，不是已证明的正文改善方案。

如未来另行实施，最小范围只能是：新候选的正文协议组装、版本及读取器选择、48诊断路径适配和受影响回归。保留47正文语义、框架契约、范围准备、Plan提问指导、完整输入及所有校验；不增加模型阶段，不回改47/48冻结指导，不切默认。

离线应先证明合法响应归一化的内部来源、独立题答及覆盖记录等价；漏答案、伪造来源、错引文和组装后缺覆盖仍拒绝；阶段修复诊断定位正确。只有新增证据支持时才值得安排小规模受控实验。协议可靠性与正文/作品收益必须分别报告。
