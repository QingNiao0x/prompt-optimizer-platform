package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 回放真实漏网问题，并用未知、变更、冲突和专业任务保护有效提问。 */
class PlanningDecisionReplayTest {
    private final PlanQuestionFilter filter = new PlanQuestionFilter();

    /** 两个匹配键是必要条件，不能在地区缺失的候选中替代已经明确的地区限制。 */
    @Test
    void shouldRejectSufficientMatchKeysThatBypassTheKnownRegionPredicate() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-sufficient-keys-region-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(),
                    mapper.treeToValue(replay.path("context"), PlanningContextDigest.class));
            var policy = PlanningDecisionPolicy.from(request);
            var question = mapper.treeToValue(replay.path("questions").get(0), PlanQuestion.class);
            var unsafe = question.options().get(1);
            for (String candidate : List.of(unsafe.description(), unsafe.answer(),
                    "只需姓名与身份证号匹配即可提示自动填充。", "仅凭姓名和身份证号匹配即视为当前地区有记录。")) {
                assertThatThrownBy(() -> policy.validateCandidate(candidate, "questions.options.answer"))
                        .isInstanceOf(ProviderResponseValidationException.class);
            }
        }
    }

    /** 缺失处理、分阶段查询、局部禁令、新对象与重开范围仍能正常表达，不因提到地址为空而全禁。 */
    @Test
    void shouldKeepMissingRegionChoicesThatRetainThePredicateOrChangeAnotherSubject() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-sufficient-keys-region-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var policy = PlanningDecisionPolicy.from(new PlanningProviderRequest(raw, "", List.of(), digest));
            for (String candidate : List.of("地址为空或无法解析时，先保留待核查状态，不提示自动填充。",
                    "地址为空时仍按姓名和身份证号匹配候选，但仍需满足当前用户地区范围条件后才提示填充。",
                    "仅按姓名和身份证号查询候选，再由服务端按当前用户地区范围过滤，符合范围后才提示填充。",
                    "当前地区有匹配记录时，只需姓名与身份证号匹配即可提示自动填充。",
                    "地址为空时不得仍按姓名和身份证号匹配并提示自动填充。",
                    "仅凭姓名和身份证号匹配不能视为当前地区有记录。",
                    "退款流程只需姓名与身份证号匹配即可提示自动填充。")) {
                org.assertj.core.api.Assertions.assertThatCode(() -> policy.validateCandidate(candidate, "questions.options.answer"))
                        .doesNotThrowAnyException();
            }
            var reopened = PlanningDecisionPolicy.from(new PlanningProviderRequest(raw
                    + "本次重新选择地区范围，地区数据缺失的匹配条件尚未确定。", "", List.of(), digest));
            org.assertj.core.api.Assertions.assertThatCode(() -> reopened.validateCandidate(
                    "只需姓名与身份证号匹配即可提示自动填充。", "questions.options.answer")).doesNotThrowAnyException();
        }
    }

    /** 并列分支保留语句边界，不把“无匹配不弹窗、接口异常”误识别成另一个业务接口。 */
    @Test
    void shouldReuseKnownTestBranchesWithoutConcatenatingSubjects() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-punctuation-scope-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(),
                    mapper.treeToValue(replay.path("context"), PlanningContextDigest.class));
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("fill_confirm_ui", "region_source");
            var coverage = questions.get(1);
            for (String punctuation : List.of("、", "，", ",")) {
                var choices = coverage.options().stream().map(option -> new PlanOption(option.id(), option.label(),
                        option.description().replace("、", punctuation), option.answer().replace("、", punctuation), false)).toList();
                var variant = new PlanQuestion(coverage.id(), coverage.question(), coverage.hint(), coverage.type(), choices,
                        List.of(), true);
                assertThat(filter.filter(List.of(variant), request)).isEmpty();
            }
        }
    }

    /** 标点归一化不得吞掉新业务、缺项反馈、性能条件或用户主动重开的测试范围。 */
    @Test
    void shouldKeepNewConditionsAndReopenedCoverageAfterPunctuationNormalization() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-punctuation-scope-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var coverage = mapper.treeToValue(replay.path("questions").get(1), PlanQuestion.class);
            for (String extra : List.of("测试无匹配不弹窗、退款接口异常时通知财务。",
                    "测试姓名缺项时提示用户并允许重试。", "测试接口响应时间不超过200毫秒。",
                    "测试覆盖先查详情再填充退款流程。")) {
                var choices = new ArrayList<>(coverage.options());
                choices.add(new PlanOption("new", "其他要求", extra, extra, false));
                var extended = new PlanQuestion(coverage.id(), coverage.question(), coverage.hint(), coverage.type(), choices,
                        List.of(), true);
                assertThat(filter.filter(List.of(extended), request)).containsExactly(extended);
            }
            assertThat(filter.filter(List.of(coverage), new PlanningProviderRequest(raw
                    + "本次希望用户自行选择测试覆盖范围。", "", List.of(), digest))).containsExactly(coverage);
        }
    }

    /** 同一目标空值资格与长修饰语的测试覆盖继承已定规则，非弹窗反馈和重试保持未知。 */
    @Test
    void shouldInheritEmptyTargetEligibilityAndAvoidTruncatingGenericTestSubjects() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-target-empty-and-long-scope-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("Q1", "Q3", "Q4");
            var sourceEmpty = free("source-empty", "详情源字段是空字符串时，应写入空值还是保留目标原值？");
            var refund = free("new-target", "本次是否需要同时测试仅对其中一个新的退款调查模块进行填充的流程？");
            assertThat(filter.filter(List.of(sourceEmpty, refund), request)).containsExactly(sourceEmpty, refund);
        }
    }

    /** 长对象表达不截成尾部假名字，真实退款对象、缺项反馈或用户重开仍不得丢失。 */
    @Test
    void shouldKeepLongConcreteModuleNamesAndIndependentFeedbackChoices() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-target-empty-and-long-scope-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var coverage = mapper.treeToValue(replay.path("questions").get(4), PlanQuestion.class);
            for (String effect : List.of("补测试覆盖当前新增的退款调查模块。", "姓名缺项时弹窗提示。")) {
                var choices = new ArrayList<>(coverage.options());
                choices.add(new PlanOption("new", "其他要求", effect, effect, false));
                var extended = new PlanQuestion(coverage.id(), coverage.question(), coverage.hint(), coverage.type(), choices, List.of(), true);
                assertThat(filter.filter(List.of(extended), request)).containsExactly(extended);
            }
            var eligibility = mapper.treeToValue(replay.path("questions").get(1), PlanQuestion.class);
            assertThat(filter.filter(List.of(eligibility), new PlanningProviderRequest(raw
                    + "本次重新选择写入条件。", "", List.of(), digest))).containsExactly(eligibility);
        }
    }

    /** 工程分类、泛指影响模块和既有异常提示交给核查，地址缺失与首次确认方式仍保持可问。 */
    @Test
    void shouldRecognizeGenericEngineeringChoicesWithoutTreatingThemAsNewBusiness() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-generic-engineering-classification-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q1", "q5", "q6");
        }
    }

    /** 同类候选一旦加入真实新业务、消息渠道、数据库迁移或主动选型就保留整题。 */
    @Test
    void shouldKeepConcreteNewTargetsAndUserRequestedEngineeringDecisions() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-generic-engineering-classification-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            for (int index : List.of(1, 2, 3, 6)) {
                var original = mapper.treeToValue(replay.path("questions").get(index), PlanQuestion.class);
                String extra = switch (index) {
                    case 1 -> "接口异常时发送邮件通知管理员。";
                    case 2 -> "补后端退款接口的单元测试。";
                    case 3 -> "同时修改新的退款模块。";
                    default -> "前后端均改，并进行数据库结构迁移。";
                };
                var choices = new ArrayList<>(original.options());
                choices.add(new PlanOption("new", "其他方案", extra, extra, false));
                var extended = new PlanQuestion(original.id(), original.question(), original.hint(), original.type(), choices, List.of(), true);
                assertThat(filter.filter(List.of(extended), request)).containsExactly(extended);
            }
            var style = mapper.treeToValue(replay.path("questions").get(1), PlanQuestion.class);
            assertThat(filter.filter(List.of(style), new PlanningProviderRequest(raw
                    + "本次异常提醒方式需要用户选择。", "", List.of(), digest))).containsExactly(style);
        }
    }

    /** 同任务详情调用继承通用失败提醒；禁令、轻提示和其他业务不能被误判成静默失败。 */
    @Test
    void shouldPreserveTheKnownErrorReminderForDetailFailures() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-detail-error-inheritance-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var policy = PlanningDecisionPolicy.from(new PlanningProviderRequest(raw, "", List.of(), digest));
            String unsafe = replay.path("questions").get(3).path("options").get(1).path("answer").asText();
            assertThatThrownBy(() -> policy.validateCandidate(unsafe, "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            assertThatThrownBy(() -> policy.validateCandidate("详情查询失败时阻断手工录入。", "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            for (String allowed : List.of("详情查询失败时不得静默中止填充，不得不提示用户。",
                    "详情接口失败时不弹窗，但给出轻量提示并允许手工录入。",
                    "详情查询失败时不额外提示，但保留现有错误提醒。",
                    "退款详情查询失败时不提示用户。")) policy.validateCandidate(allowed, "questions.options.answer");
            PlanningDecisionPolicy.from(new PlanningProviderRequest(raw + "本次重新选择异常处理方式。", "", List.of(), digest))
                    .validateCandidate(unsafe, "questions.options.answer");
            PlanningDecisionPolicy.from(new PlanningProviderRequest(raw.replace("接口异常提醒但不阻断手工录入",
                    "只针对列表接口异常提醒但不阻断手工录入"), "", List.of(), digest))
                    .validateCandidate(unsafe, "questions.options.answer");
        }
    }

    /** 通用失败行为不重复提问；复合候选中的重试是独立未知，不能为去重一并删除。 */
    @Test
    void shouldReuseDetailErrorBehaviourWithoutDiscardingNewRetryChoices() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-detail-error-inheritance-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            var question = mapper.treeToValue(replay.path("questions").get(3), PlanQuestion.class);
            var knownOnly = new PlanQuestion(question.id(), question.question(), question.hint(), question.type(),
                    List.of(question.options().getFirst()), List.of(), true);
            assertThat(filter.filter(List.of(knownOnly), request)).isEmpty();
            var withRetry = new PlanQuestion(question.id(), question.question(), question.hint(), question.type(),
                    List.of(question.options().getFirst(), question.options().get(2)), List.of(), true);
            assertThat(filter.filter(List.of(withRetry), request)).containsExactly(withRetry);
            var unknown = free("missing-detail", "详情字段缺失或只有部分返回时，是否允许部分填充？");
            assertThat(filter.filter(List.of(unknown), request)).containsExactly(unknown);
            var refund = free("refund-error", "退款接口异常提醒后是否继续手工录入？");
            assertThat(filter.filter(List.of(refund), request)).containsExactly(refund);
        }
    }

    /** 模块范围按已有目标及候选对象核对，不能依赖题干是否再次出现“自动填充”。 */
    @Test
    void shouldInheritDeclaredModuleScopeFromGenericModificationQuestions() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-generic-module-and-test-layer-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var module = mapper.treeToValue(replay.path("questions").get(0), PlanQuestion.class);
            assertThat(filter.filter(List.of(module), request)).isEmpty();
            var choices = new ArrayList<>(module.options());
            choices.add(new PlanOption("refund", "退款模块", "新增独立业务对象。", "同时修改退款模块。", false));
            var newScope = new PlanQuestion(module.id(), module.question(), module.hint(), module.type(), choices, List.of(), true);
            assertThat(filter.filter(List.of(newScope), request)).containsExactly(newScope);
            assertThat(filter.filter(List.of(module), new PlanningProviderRequest(raw
                    + "本次重新选择修改范围，需要用户决定。", "", List.of(), digest))).containsExactly(module);
        }
    }

    /** 测试层的词序和界面测试别名不改变核查职责；新业务、数据与用户主动选择继续保留。 */
    @Test
    void shouldDelegateTestLayersWithoutHidingIndependentChoicesInTheirOptions() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-generic-module-and-test-layer-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var layers = mapper.treeToValue(replay.path("questions").get(3), PlanQuestion.class);
            assertThat(filter.filter(List.of(layers), request)).isEmpty();
            for (String independent : List.of("新增退款流程的界面测试", "完整退款流程的界面测试",
                    "使用医院记录作为测试数据", "十万并发负载测试")) {
                var choices = new ArrayList<>(layers.options());
                choices.add(new PlanOption("new", "界面测试", independent, independent, false));
                var extended = new PlanQuestion(layers.id(), layers.question(), layers.hint(), layers.type(), choices, List.of(), true);
                assertThat(filter.filter(List.of(extended), request)).containsExactly(extended);
            }
            assertThat(filter.filter(List.of(layers), new PlanningProviderRequest(raw
                    + "本次由我选择测试层。", "", List.of(), digest))).containsExactly(layers);
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q2", "q3");
        }
    }

    /** 分布依赖的检验选择不能被候选中的预先定案替换；列表、条件化方案及禁止表述仍合法。 */
    @Test
    void shouldPreserveFutureDataSelectionWithoutRejectingConditionalOrCandidateMethods() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-future-method-selection-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var policy = PlanningDecisionPolicy.from(new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest));
            String unsafe = replay.path("questions").get(4).path("options").get(2).path("answer").asText();
            assertThatThrownBy(() -> policy.validateCandidate(unsafe, "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            assertThatThrownBy(() -> policy.validateCandidate("不得编造数据，但预先指定一个主要检验。", "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            for (String safe : List.of("列出候选检验，具体检验依据未来数据分布选择。",
                    "不得预先指定一个主要检验。", "禁止提前确定具体检验。",
                    "可以预先指定主要检验的候选，但实际采用仍须依据未来数据分布选择。")) {
                policy.validateCandidate(safe, "questions.options.answer");
            }
            PlanningDecisionPolicy.from(input("为研究准备预注册方案，主要检验尚未选定。"))
                    .validateCandidate(unsafe, "questions.options.answer");
        }
    }

    /** “验收范围如何界定”仍是影响路径核查，新缺失值、压测和业务对象不得被泛化为已知。 */
    @Test
    void shouldDelegateTestAcceptanceScopeWithoutHidingDateFallbackOrNewAcceptanceTargets() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-test-acceptance-scope-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q3");
            var scope = questions.getFirst();
            for (String newTarget : List.of("验证退款流程", "新增十万用户负载测试", "测试数据从医院记录获取",
                    "填充流程中的字段来源", "界面流程的日志记录")) {
                var choices = new ArrayList<>(scope.options());
                choices.add(new PlanOption("new-target", newTarget, "", "补测试覆盖" + newTarget + "。", false));
                var extended = new PlanQuestion(scope.id(), scope.question(), scope.hint(), scope.type(), choices, List.of(), true);
                assertThat(filter.filter(List.of(extended), request)).containsExactly(extended);
            }
            assertThat(filter.filter(List.of(scope), new PlanningProviderRequest(raw
                    + "本次由用户选择测试覆盖范围。", "", List.of(), digest))).containsExactly(scope);
        }
    }

    /** 交付方法提纲已确定时，组织既定设计的章节是编辑职责，具体评分及新参数仍需要确认。 */
    @Test
    void shouldDelegateKnownMethodOutlineContentsWithoutHidingNewMethodParameters() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-method-scope-content-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("plan_interaction_rounds", "fair_control_info_delivery", "task_material_source", "evaluation_dimensions");
            var outline = questions.getLast();
            for (String newParameter : List.of("新增效应量阈值为0.5。", "研究设计改为30个任务。", "具体采用Kappa指标。")) {
                var choices = new ArrayList<>(outline.options());
                choices.add(new PlanOption("new", "研究设计", "", newParameter, false));
                var newChoice = new PlanQuestion(outline.id(), outline.question(), outline.hint(), outline.type(), choices, List.of(), true);
                assertThat(filter.filter(List.of(newChoice), request)).containsExactly(newChoice);
            }
            assertThat(filter.filter(List.of(outline), new PlanningProviderRequest(raw
                    + "本次重新选择写作范围，需要用户决定。", "", List.of(), digest))).containsExactly(outline);
            var scoring = new PlanQuestion(outline.id(), outline.question() + "具体采用什么评分尺度？",
                    outline.hint(), outline.type(), outline.options(), List.of(), true);
            assertThat(filter.filter(List.of(scoring), request)).containsExactly(scoring);
        }
    }

    /** 真实复合问法继承既定匹配范围与测试职责，独立重试、缺失和新增业务保持可问。 */
    @Test
    void shouldInheritMatchingScopeForItsPromptAndRecognizeGenericModuleReferences() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-scope-trigger-and-generic-object-replay.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(fixture);
            String raw = replay.path("rawPrompt").asText();
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var input = new PlanningProviderRequest(raw, "", List.of(), digest);
            var questions = new ArrayList<PlanQuestion>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, input)).extracting(PlanQuestion::id).containsExactly("q3");

            var prompt = questions.getFirst();
            assertThat(filter.filter(List.of(prompt), new PlanningProviderRequest(raw
                    + "本次提示触发范围尚未确定，需要用户选择。", "", List.of(), digest)))
                    .containsExactly(prompt);
            // 匹配范围已知并不决定年龄人群、确认粒度或交互方式；这些新维度仍必须可问。
            for (String newDimension : List.of("匹配范围内不同年龄人群是否采用同一提示方式？",
                    "匹配成功后采用逐字段还是整批确认？", "匹配成功时是否新增确认按钮？")) {
                var independent = new PlanQuestion("new-prompt-choice", prompt.question() + newDimension,
                        prompt.hint(), prompt.type(), prompt.options(), List.of(), true);
                assertThat(filter.filter(List.of(independent), input)).containsExactly(independent);
            }
            var populationOption = new PlanQuestion("population", prompt.question(), prompt.hint(), prompt.type(),
                    List.of(new PlanOption("by-age", "按年龄分别提示", "不同年龄人群单独决定提示交互方式。",
                            "在已定匹配范围内，按年龄人群分别定义提示方式。", false)), List.of(), true);
            assertThat(filter.filter(List.of(populationOption), input)).containsExactly(populationOption);
            var tests = questions.get(2);
            var refund = new PlanQuestion(tests.id(), tests.question(), tests.hint(), tests.type(),
                    List.of(tests.options().getFirst(), new PlanOption("refund", "覆盖新退款模块", "", 
                            "补测试覆盖新增退款模块的基线匹配与填充行为。", false)), List.of(), true);
            var missing = free("region-missing", "下级地区信息缺失时，是否需要提示？");
            assertThat(filter.filter(List.of(refund, missing), input)).containsExactly(refund, missing);
            assertThat(filter.filter(List.of(tests), new PlanningProviderRequest(raw
                    + "这次希望用户自行选择测试覆盖范围。", "", List.of(), digest))).containsExactly(tests);
        }
    }
    private static final String SOURCE = "[PROJECT_SOURCE] src/surveys/baselineApi.ts："
            + "export const BASELINE_FILL_FIELDS = ['name', 'idNumber', 'residenceAddress'];";
    private static final String COMPLETE = "完善基线匹配。用户录入效果评价时进行匹配；"
            + "包含下级地区但排除其他同级地区；当前地区有匹配记录时提示用户是否自动填充。"
            + "用户确认后先查询详情，再填 BASELINE_FILL_FIELDS。仅填 null 或空字符串，保留 0 和 false。"
            + "取消保持原值，接口异常提醒但不阻断手工录入。完善上述逻辑并补测试，不扩展业务范围。";

    @Test
    void shouldRejectRemovingConfirmationInformationFromAnEqualInformationControl() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-parity-information-omission-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var policy = PlanningDecisionPolicy.from(new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest));
            String unsafe = replay.path("questions").get(1).path("options").get(2).path("answer").asText();
            assertThatThrownBy(() -> policy.validateCandidate(unsafe, "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            assertThatThrownBy(() -> policy.validateCandidate("公平对照不获得确认信息。", "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            policy.validateCandidate("公平对照的初始提示词已包含与 Plan 相同的确认信息，不额外补充确认信息。", "questions.options.answer");
            policy.validateCandidate("公平对照不得省略确认信息。", "questions.options.answer");
            policy.validateCandidate("对照组获得相同确认信息，但不再进行 Plan 交互。", "questions.options.answer");
            policy.validateCandidate("直接增强条件仅使用初始提示词，不额外补充确认信息。", "questions.options.answer");
            PlanningDecisionPolicy.from(input("拟定研究设计，目前还未决定对照组是否提供确认信息。"))
                    .validateCandidate(unsafe, "questions.options.answer");
        }
    }

    @Test
    void shouldDelegateExistingImpactPathsButKeepAddressAndNonModalFeedbackUnknown() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-engineering-layer-and-target-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q1", "q2", "q3");
            var testing = questions.get(3);
            var unknownTarget = new PlanQuestion(testing.id(), testing.question(), testing.hint(), testing.type(),
                    List.of(new PlanOption("refund", "退款接口", "覆盖另一个业务。", "补测试覆盖退款接口。", false)), List.of(), true);
            assertThat(filter.filter(List.of(unknownTarget), request)).containsExactly(unknownTarget);
        }
    }

    @Test
    void shouldNotDelegateNewBackendBusinessRulesOrAUserRequestedLayerDecision() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-engineering-layer-and-target-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            String raw = replay.path("rawPrompt").asText();
            var layer = mapper.treeToValue(replay.path("questions").get(4), PlanQuestion.class);
            var extended = new PlanQuestion(layer.id(), "本次是否同时修改服务端地区过滤逻辑和新增跨租户权限？", "",
                    layer.type(), layer.options(), List.of(), true);
            assertThat(filter.filter(List.of(extended), new PlanningProviderRequest(raw, "", List.of(), digest)))
                    .containsExactly(extended);
            assertThat(filter.filter(List.of(layer), new PlanningProviderRequest(raw
                    + "本次由我选择前后端修改范围。", "", List.of(), digest))).containsExactly(layer);
            assertThat(filter.filter(List.of(layer), input("仅设计地区匹配方案，需要选择前后端职责。")))
                    .containsExactly(layer);
        }
    }

    @Test
    void shouldDelegateDeclaredModuleTestsWithoutLosingNewObjectsOrFeedback() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-detail-and-test-interrogative-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            String raw = replay.path("rawPrompt").asText();
            var request = new PlanningProviderRequest(raw, "", List.of(), digest);
            var testing = mapper.treeToValue(replay.path("questions").get(4), PlanQuestion.class);
            assertThat(filter.filter(List.of(testing), request)).isEmpty();
            var refund = new PlanQuestion(testing.id(), "是否还需要覆盖两个退款调查模块的界面交互？", "",
                    testing.type(), testing.options(), List.of(), true);
            assertThat(filter.filter(List.of(refund), request)).containsExactly(refund);
            var options = new ArrayList<>(testing.options());
            options.add(new PlanOption("missing-feedback", "缺项反馈", "需要决定是否提示。",
                    "缺少姓名时提示用户补全。", false));
            var feedback = new PlanQuestion(testing.id(), testing.question(), testing.hint(), testing.type(),
                    options, List.of(), true);
            assertThat(filter.filter(List.of(feedback), request)).containsExactly(feedback);
            assertThat(filter.filter(List.of(testing), new PlanningProviderRequest(raw
                    + "本次由我选择测试层和覆盖范围。", "", List.of(), digest))).containsExactly(testing);
        }
    }

    @Test
    void shouldKeepDetailJudgementsButRejectExtraFillFieldsOutsideTheChosenSet() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-detail-and-test-interrogative-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            var fields = mapper.treeToValue(replay.path("questions").get(2), PlanQuestion.class);
            // 判断记录状态与填充字段不是同一个决定；保留未知用途，但不能允许违反已定填充范围。
            assertThat(filter.filter(List.of(fields), request)).containsExactly(fields);
            var policy = PlanningDecisionPolicy.from(request);
            assertThatThrownBy(() -> policy.validateCandidate(fields.options().get(1).answer(), "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            policy.validateCandidate("详情中的其他字段只用于判断记录状态，不参与填充。", "questions.options.answer");
            policy.validateCandidate("不得让其他字段参与填充。", "questions.options.answer");
            policy.validateCandidate("还需确定退款表单中其他字段参与填充的规则。", "questions.options.answer");
            var reopened = PlanningDecisionPolicy.from(new PlanningProviderRequest(replay.path("rawPrompt").asText()
                    + "本次重新选择填充字段范围。", "", List.of(), digest));
            reopened.validateCandidate(fields.options().get(1).answer(), "questions.options.answer");
        }
    }

    @Test
    void shouldNotReopenTheChosenFieldSetWithOtherFieldWording() {
        var request = input(COMPLETE, SOURCE);
        var selection = free("fields", "自动填充时是否还需要填充 BASELINE_FILL_FIELDS 之外的其他字段？");
        assertThat(filter.filter(List.of(selection), request)).isEmpty();
        var judgement = free("judge", "除填充 BASELINE_FILL_FIELDS 外，详情中的其他字段是否还用于校验？");
        assertThat(filter.filter(List.of(judgement), request)).containsExactly(judgement);
        assertThat(filter.filter(List.of(selection), input(COMPLETE + "本次重新选择填充字段范围。", SOURCE)))
                .containsExactly(selection);
    }

    @Test
    void shouldInheritDeclaredBusinessModulesAndTheirRequiredTestBranches() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-declared-modules-and-range-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("region_source", "region_empty", "fill_confirm_scope");
            var modules = questions.get(3);
            var other = new PlanQuestion(modules.id(), modules.question(), modules.hint(), modules.type(),
                    List.of(new PlanOption("refund", "退款模块", "新增退款调查。",
                            "本次同时实现 refund-survey 的退款模块。", false)), List.of(), true);
            assertThat(filter.filter(List.of(other), request)).containsExactly(other);
            var tests = questions.getLast();
            var extended = new ArrayList<>(tests.options());
            extended.add(new PlanOption("feedback", "缺项反馈", "需要决定是否提示。",
                    "缺少姓名时提示用户补全。", false));
            var missingFeedback = new PlanQuestion(tests.id(), tests.question(), tests.hint(), tests.type(),
                    extended, List.of(), true);
            assertThat(filter.filter(List.of(missingFeedback), request)).containsExactly(missingFeedback);
        }
    }

    @Test
    void shouldNotUseAttachmentModuleCountToExpandASingleModuleOrOverrideAReopenedScope() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-declared-modules-and-range-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var modules = mapper.treeToValue(replay.path("questions").get(3), PlanQuestion.class);
            String raw = replay.path("rawPrompt").asText();
            assertThat(filter.filter(List.of(modules), new PlanningProviderRequest(raw.replace(
                    "防治知识知晓情况和防护行为调查", "仅防护行为调查"), "", List.of(), digest)))
                    .containsExactly(modules);
            assertThat(filter.filter(List.of(modules), new PlanningProviderRequest(raw
                    + "本次重新选择调查模块范围。", "", List.of(), digest))).containsExactly(modules);
        }
    }

    @Test
    void shouldRejectOmittingRegionConditionsWithoutRejectingTheProhibition() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-declared-modules-and-range-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var policy = PlanningDecisionPolicy.from(new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest));
            String unsafe = replay.path("questions").get(1).path("options").get(2).path("answer").asText();
            assertThatThrownBy(() -> policy.validateCandidate(unsafe, "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            policy.validateCandidate("不得不附加地区条件进行查询。", "questions.options.answer");
            policy.validateCandidate("不能不带地区查询。", "questions.options.answer");
            policy.validateCandidate("前端不带地区查询参数，服务端仍按当前用户所属地区与下级地区过滤。", "questions.options.answer");
        }
    }

    @Test
    void shouldKeepModuleQuestionsWhenDocumentDeclarationsConflictOrReferencedCodeIsMissing() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-declared-modules-and-range-replay.json")) {
            var replay = mapper.readTree(fixture);
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var modules = mapper.treeToValue(replay.path("questions").get(3), PlanQuestion.class);
            String raw = replay.path("rawPrompt").asText();
            var conflicting = new ArrayList<>(digest.fileSummaries());
            conflicting.add("[PROJECT_DOCUMENT] docs/另一份声明.md：两个调查模块为 know-survey 与 different-survey，均调用 baselineApi.ts。");
            assertThat(filter.filter(List.of(modules), input(raw, conflicting.toArray(String[]::new))))
                    .containsExactly(modules);
            assertThat(filter.filter(List.of(modules), input(raw, digest.fileSummaries().stream()
                    .filter(value -> !value.startsWith("[PROJECT_SOURCE]")).toArray(String[]::new))))
                    .containsExactly(modules);
        }
    }

    @Test
    void shouldInheritTheInteractiveResearchGoalAndMethodSectionInsteadOfReopeningThem() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-mode-definition-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var request = input(replay.path("rawPrompt").asText());
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("q_task_source", "q_control_parity", "q_consistency_report");
            var policy = PlanningDecisionPolicy.from(request);
            for (var option : questions.get(1).options().subList(1, 3)) {
                assertThatThrownBy(() -> policy.validateCandidate(option.answer(), "questions.options.answer"))
                        .isInstanceOf(ProviderResponseValidationException.class);
            }
            policy.validateCandidate("Plan 不得界定为套用固定计划模板生成，不涉及用户交互。", "questions.options.answer");
            var rounds = free("rounds", "Plan 最多提问几轮以及如何终止？");
            assertThat(filter.filter(List.of(rounds), request)).containsExactly(rounds);
            var boundedDefinition = new PlanQuestion("bounded", "方法部分应如何界定 Plan 实验条件？", "",
                    PlanQuestionType.SINGLE_CHOICE, List.of(new PlanOption("bound", "限制为两轮", "需要明确交互预算。",
                    "Plan 先提问，最多进行2轮问答后再生成。", false)), List.of(), true);
            assertThat(filter.filter(List.of(boundedDefinition), request)).containsExactly(boundedDefinition);
            assertThat(filter.filter(List.of(questions.get(1)), input(replay.path("rawPrompt").asText()
                    + "本次重新选择 Plan 定义。"))).containsExactly(questions.get(1));
        }
    }

    @Test
    void shouldValidateConfirmationContentAliasesWithoutRemovingResearchDefinitions() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-confirmation-content-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var request = input(replay.path("rawPrompt").asText());
            assertThat(filter.filter(questions, request)).containsExactlyElementsOf(questions);
            var policy = PlanningDecisionPolicy.from(request);
            assertThatThrownBy(() -> policy.validateCandidate(questions.get(1).options().get(2).answer(), "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            policy.validateCandidate("公平对照的确认信息内容完全相同，但呈现方式可以不同。", "questions.options.answer");
        }
    }

    @Test
    void shouldDelegateMixedEngineeringDimensionsAndKeepActualRegionDataUnknown() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-mixed-engineering-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("prompt_ui", "region_field");
            var mixed = questions.get(2);
            List<PlanOption> extended = new ArrayList<>(mixed.options());
            extended.add(new PlanOption("new-feedback", "缺项补全提示", "确认是否新增提示。",
                    "测试覆盖缺项时提示用户补全字段。", false));
            var newEffect = new PlanQuestion(mixed.id(), mixed.question(), mixed.hint(), mixed.type(), extended, List.of(), true);
            assertThat(filter.filter(List.of(newEffect), request)).containsExactly(newEffect);
            var otherInterface = new PlanQuestion(mixed.id(), mixed.question(), mixed.hint(), mixed.type(),
                    List.of(new PlanOption("other-interface", "接口层", "覆盖退款接口。", "测试覆盖退款接口。", false)), List.of(), true);
            assertThat(filter.filter(List.of(otherInterface), request)).containsExactly(otherInterface);
        }
    }

    @Test
    void shouldResolveDeclaredTestTargetsWithoutLosingNewFeedbackFromTheTenthLiveCandidate() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-declared-test-targets-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q1", "q2", "q3");
            var testing = questions.getLast();
            var changed = new PlanQuestion(testing.id(), testing.question(), testing.hint(), testing.type(),
                    List.of(new PlanOption("other", "know-survey 与退款模块", "覆盖新增退款模块。",
                            "测试覆盖 know-survey 与退款模块。", false)), List.of(), true);
            assertThat(filter.filter(List.of(changed), request)).containsExactly(changed);
        }
    }

    @Test
    void shouldKeepResearchDefinitionsWithoutReopeningBlindingFromTheNinthLiveCandidate() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-research-blinding-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var request = input(replay.path("rawPrompt").asText());
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("outcome_definition", "task_source", "fair_control_definition", "method_outline_scope");
            var roundsOnly = questions.get(3).options().get(1);
            assertThatThrownBy(() -> PlanningDecisionPolicy.from(request)
                    .validateCandidate(roundsOnly.answer(), "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void shouldKeepNewBlindingProceduresAndExplicitChanges() {
        String raw = "为研究准备论文方法，两名独立评审盲评，另设获得同样确认信息的公平对照。";
        var visible = free("visible", "评审是否知道每个输出对应的实验条件？");
        var order = free("order", "盲评输出的匿名编码和展示顺序如何随机化？");
        var scores = free("scores", "两名评审采用哪些评分维度和一致性口径？");
        assertThat(filter.filter(List.of(visible, order, scores), input(raw))).containsExactly(order, scores);
        assertThat(filter.filter(List.of(visible), input(raw + "本次盲评要求需要重新选择。"))).containsExactly(visible);
    }

    @Test
    void shouldRejectKnownConditionDisclosureWithoutRejectingItsProhibitionOrUnspecifiedStudies() {
        String raw = "为研究准备论文方法，两名独立评审盲评，另设获得同样确认信息的公平对照。";
        var policy = PlanningDecisionPolicy.from(input(raw));
        for (String disclosure : List.of("评审知道每个输出对应的实验条件，但独立评分。", "评审会知晓实验分组。")) {
            assertThatThrownBy(() -> policy.validateCandidate(disclosure, "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
        for (String prohibition : List.of("评审不知道每个输出对应的实验条件。", "评审不会知晓实验分组。", "禁止评审看到实验条件。")) {
            policy.validateCandidate(prohibition, "questions.options.answer");
        }
        PlanningDecisionPolicy.from(input("为研究准备论文方法，评审方式尚未确定。"))
                .validateCandidate("评审知道每个输出对应的实验条件。", "questions.options.answer");
    }

    @Test
    void shouldKeepFairnessImplementationChoicesWhenTheInformationRequirementIsPreserved() {
        String raw = "为研究准备论文方法，另设获得同样确认信息的公平对照。";
        var policy = PlanningDecisionPolicy.from(input(raw));
        for (String valid : List.of("两组公平对照在确认内容和确认轮次上均相同，仅呈现方式不同。",
                "两组公平对照各自生成确认内容后核对，保证确认信息相同。", "不得允许公平对照获得不同确认内容。")) {
            policy.validateCandidate(valid, "questions.options.answer");
        }
        assertThatThrownBy(() -> policy.validateCandidate("两组公平对照的确认内容可以不同。", "questions.options.answer"))
                .isInstanceOf(ProviderResponseValidationException.class);
        PlanningDecisionPolicy.from(input("为研究准备论文方法，公平对照的确认信息尚未确定。"))
                .validateCandidate("两组公平对照各自生成确认内容。", "questions.options.answer");
    }

    @Test
    void shouldDelegateTestTargetsWithoutTreatingMissingRegionOrConfirmationGranularityAsKnown() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-test-targets-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("region_source", "region_empty", "confirm_scope");
            var selection = questions.getLast();
            var reopened = new PlanningProviderRequest(request.rawPrompt() + "本次测试覆盖范围需要用户选择。", "", List.of(), digest);
            assertThat(filter.filter(List.of(selection), reopened)).containsExactly(selection);
            var other = new PlanQuestion("other-module", "补测试是否需要覆盖新的退款模块？", "",
                    selection.type(), selection.options(), List.of(), true);
            assertThat(filter.filter(List.of(other), request)).containsExactly(other);
        }
    }

    @Test
    void shouldDelegateApiTestsRejectRegionBypassAndKeepIndependentQuestionsFromTheSeventhLiveCandidate() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-region-and-test-layer-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("region_source", "region_empty", "patient_region_field", "fill_confirm_scope");
            var unknownRegion = questions.get(1);
            var policy = PlanningDecisionPolicy.from(request);
            var bypass = unknownRegion.options().get(1);
            assertThatThrownBy(() -> policy.validateCandidate(bypass.answer(), "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            assertThat(PlanRecommendationAligner.align(unknownRegion, request).options())
                    .noneMatch(PlanOption::recommended);
            var explicit = new PlanningProviderRequest(request.rawPrompt() + "\n" + unknownRegion.options().getFirst().answer(),
                    "", List.of(), digest);
            assertThat(PlanRecommendationAligner.align(unknownRegion, explicit).options())
                    .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("no_query");
        }
    }

    @Test
    void shouldRejectPositiveRegionBypassAliasesWithoutRejectingTheirProhibition() {
        String raw = "完善基线匹配，按当前用户所属地区匹配患者基线，包含下级地区但排除其他同级地区。" + COMPLETE;
        var policy = PlanningDecisionPolicy.from(input(raw, SOURCE));
        for (String action : List.of("不做地区范围过滤", "不限定地区", "不进行地区过滤", "不执行地区过滤", "跳过地区过滤")) {
            assertThatThrownBy(() -> policy.validateCandidate("地区缺失时仍按姓名和身份证号查询，" + action + "。",
                    "questions.options.answer")).as(action).isInstanceOf(ProviderResponseValidationException.class);
            policy.validateCandidate("地区缺失时不得" + action + "查询。", "questions.options.answer");
        }
        var reopened = PlanningDecisionPolicy.from(input(raw + "本次地区范围需要重新选择。", SOURCE));
        reopened.validateCandidate("地区缺失时仍查询，不限定地区。", "questions.options.answer");
    }

    @Test
    void shouldKeepMissingInputFeedbackWithoutReopeningNonEmptyValuesOrKnownTestBranches() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-write-and-coverage-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q1");
            var coverage = questions.getLast();
            for (var option : coverage.options()) {
                var single = new PlanQuestion(coverage.id(), coverage.question(), coverage.hint(), coverage.type(),
                        List.of(option), coverage.examples(), coverage.allowCustomAnswer());
                assertThat(filter.filter(List.of(single), request)).as("known branch %s", option.id()).isEmpty();
            }
        }
    }

    @Test
    void shouldKeepUnknownFeedbackOrAuditingEvenWhenWriteAndMatchingRulesAreKnown() {
        String raw = COMPLETE + "姓名和身份证号同时匹配，缺项时不查询。";
        var audit = free("audit", "用户确认自动填充后，已有非空值字段的审计日志如何处理？");
        var sourceEmpty = free("source-empty", "用户确认自动填充后，详情源字段为空但当前已有非空值时如何处理？");
        var feedback = new PlanQuestion("coverage-feedback", "本次补测试需要覆盖哪些范围？", "",
                PlanQuestionType.MULTIPLE_CHOICE, List.of(new PlanOption("feedback", "缺项反馈", "",
                        "测试覆盖姓名或身份证号缺项不查询，并向用户弹窗提示补齐。", false)), List.of(), true);
        var reset = new PlanQuestion("coverage-reset", "本次补测试需要覆盖哪些范围？", "",
                PlanQuestionType.MULTIPLE_CHOICE, List.of(new PlanOption("reset", "取消后的新效果", "",
                        "覆盖取消保持原值，并自动新建患者记录。", false)), List.of(), true);
        // 两个覆盖题故意使用相同题干；逐题验证，避免把文案去重误当成未知条件被吞掉。
        for (var question : List.of(audit, sourceEmpty, feedback, reset)) {
            assertThat(filter.filter(List.of(question), input(raw, SOURCE))).containsExactly(question);
        }
    }

    @Test
    void shouldNotUseKnownMatchKeysToDecideMissingInputFeedback() {
        String raw = COMPLETE + "姓名和身份证号同时匹配，缺项时不查询。";
        var rule = free("match-rule", "姓名和身份证号是否需要同时匹配，缺项时不查询？");
        var feedback = free("missing-feedback", "姓名和身份证号缺项时不查询，界面是否提醒补全？");
        var disabled = free("missing-disabled", "姓名和身份证号缺项时不查询，是否禁用自动填充入口？");
        assertThat(filter.filter(List.of(rule, feedback, disabled), input(raw, SOURCE)))
                .containsExactly(feedback, disabled);
    }

    @Test
    void shouldNotInferValueProvenanceFromTheKnownWriteBoundary() {
        var provenance = free("provenance", "用户确认自动填充后，已有非空值字段的来源标注如何处理？");
        var validation = free("validation", "用户确认自动填充后，已有非空值字段的校验规则如何处理？");
        assertThat(filter.filter(List.of(provenance, validation), input(COMPLETE, SOURCE)))
                .containsExactly(provenance, validation);
    }

    @Test
    void shouldSeparateActualUnknownsFromTheFourthLiveCandidate() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-new-conditions-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id)
                    .containsExactly("region_source", "region_empty");
            var policy = PlanningDecisionPolicy.from(request);
            var unsafe = questions.get(1).options().stream().filter(option -> option.id().equals("query_no_region")).findFirst().orElseThrow();
            assertThatThrownBy(() -> policy.validateCandidate(unsafe.answer(), "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            questions.get(1).options().stream().filter(option -> !option.id().equals("query_no_region"))
                    .forEach(option -> policy.validateCandidate(option.answer(), "questions.options.answer"));
            policy.validateCandidate("不得忽略地区条件查询；允许手工录入。", "questions.options.answer");
        }
    }

    @Test
    void shouldResolveComposedCoverageWithoutInventingTheRegionFieldName() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-composed-coverage-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(filter.filter(questions, request)).extracting(PlanQuestion::id).containsExactly("q_region_source");
            var policy = PlanningDecisionPolicy.from(request);
            var unsafe = questions.getFirst().options().getLast();
            assertThatThrownBy(() -> policy.validateCandidate(unsafe.answer(), "questions.options.answer"))
                    .isInstanceOf(ProviderResponseValidationException.class);
            policy.validateCandidate(questions.getFirst().options().getFirst().answer(), "questions.options.answer");
        }
    }

    @Test
    void shouldNotReopenFourDecisionsFromTheActualProviderReplay() throws IOException {
        var mapper = new ObjectMapper();
        JsonNode bundle = mapper.readTree(Files.readString(Path.of("..", "..", "docs", "testing", "evidence",
                "确定缺陷定向补验-2026-10-03.json")));
        JsonNode item = null;
        JsonNode plan = null;
        for (JsonNode candidate : bundle.path("cases")) {
            if ("01_software_autofill".equals(candidate.path("id").asText())) item = candidate;
        }
        for (JsonNode result : bundle.path("results")) {
            if ("01_software_autofill".equals(result.path("id").asText())
                    && "plan".equals(result.path("arm").asText())) plan = result;
        }
        assertThat(item).isNotNull();
        assertThat(plan).isNotNull();
        List<PlanQuestion> questions = new ArrayList<>();
        for (JsonNode value : plan.path("plan").path("questions")) {
            questions.add(mapper.treeToValue(value, PlanQuestion.class));
        }
        assertThat(questions).hasSize(4);
        PlanningContextDigest digest = mapper.treeToValue(plan.path("prepared").path("digest"), PlanningContextDigest.class);
        var input = new PlanningProviderRequest(item.path("rawPrompt").asText(), "", List.of(), digest);
        assertThat(filter.filter(questions, input)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "自动填充时，BASELINE_FILL_FIELDS 中的字段是否全部填充？",
            "需要把 BASELINE_FILL_FIELDS 整组用于填充，还是只选一部分？",
            "自动填充基本信息包含哪些项目？",
            "基本信息自动补齐的字段集合要如何选择？",
            "填充内容是否应该缩减到姓名与身份证号？"
    })
    void shouldMatchAChosenFieldSetAcrossQuestionAndOptionWording(String text) {
        assertThat(filter.filter(List.of(fieldChoice(text)), input(COMPLETE, SOURCE))).isEmpty();
    }

    @Test
    void shouldPreserveUnknownFeedbackAndNotReopenKnownRulesFromTheFirstLiveCandidate() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-known-rules-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var input = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(questions).hasSize(6);
            // 原文只规定缺项不查询，没有规定是否提示补全；不能用原先的零问题标签消除真实未知。
            assertThat(filter.filter(questions, input)).extracting(PlanQuestion::id).containsExactly("q1");
        }
    }

    @Test
    void shouldNotReconfirmKnownBoundariesFromTheThirdLiveCandidate() throws Exception {
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/fixtures/planning-known-boundaries-replay.json")) {
            assertThat(fixture).isNotNull();
            var replay = mapper.readTree(fixture);
            List<PlanQuestion> questions = new ArrayList<>();
            for (var value : replay.path("questions")) questions.add(mapper.treeToValue(value, PlanQuestion.class));
            var digest = mapper.treeToValue(replay.path("context"), PlanningContextDigest.class);
            var request = new PlanningProviderRequest(replay.path("rawPrompt").asText(), "", List.of(), digest);
            assertThat(questions).hasSize(4);
            var coverage = questions.getLast();
            for (var option : coverage.options()) {
                var single = new PlanQuestion(coverage.id(), coverage.question(), coverage.hint(), coverage.type(),
                        List.of(option), coverage.examples(), coverage.allowCustomAnswer());
                assertThat(filter.filter(List.of(single), request)).as("known branch %s", option.id()).isEmpty();
            }
            assertThat(filter.filter(questions, request)).isEmpty();
        }
    }

    @Test
    void shouldKeepIndependentBoundaryConditionsAndResponsibilityConflicts() {
        String raw = "完善基线匹配，按当前用户所属地区匹配患者基线，包含下级地区但排除其他同级地区。"
                + COMPLETE;
        String placement = "[PROJECT_DOCUMENT] docs/基线.md：服务端负责地区范围过滤。";
        var questions = List.of(
                free("region-source", "当前用户地区字段的来源和空值处理方式是什么？"),
                free("region-permission", "服务端地区过滤是否需要新增权限校验？"),
                free("error-timeout", "接口异常后，手动填写时的超时提示应等待多少毫秒？"),
                free("coverage-new", "补测试是否需要覆盖缓存过期后的旧数据填充？"));
        assertThat(filter.filter(questions, input(raw, SOURCE, placement))).containsExactlyElementsOf(questions);
        var responsibility = free("responsibility", "地区过滤是否以服务端返回的匹配结果为准？");
        assertThat(filter.filter(List.of(responsibility), input(raw, SOURCE, placement,
                "[PROJECT_DOCUMENT] docs/前端.md：前端负责地区范围过滤。"))).containsExactly(responsibility);
    }

    @Test
    void shouldRetainAReopenedErrorBehaviorAndRegionBoundary() {
        String raw = "完善基线匹配，按当前用户所属地区匹配患者基线，包含下级地区但排除其他同级地区。"
                + COMPLETE;
        var error = free("error-new", "接口异常提醒后，是否允许用户继续手动填写？");
        var region = free("region-new", "其他地区是否包括上级地区？");
        assertThat(filter.filter(List.of(error), input(raw + "异常处理重新设计，需要用户选择是否阻断。", SOURCE)))
                .containsExactly(error);
        assertThat(filter.filter(List.of(region), input(raw + "本次地区范围需要重新选择。", SOURCE)))
                .containsExactly(region);
    }

    @Test
    void shouldKeepATestCoverageChoiceWithAnAdditionalUnspecifiedCondition() {
        var known = new PlanOption("cancel", "取消保持原值", "", "覆盖取消保持表单原值。", false);
        var unknown = new PlanOption("privacy", "新增隐私规则", "", "覆盖新增加的身份证号脱敏规则。", false);
        var question = new PlanQuestion("coverage", "补测试是否覆盖取消与新的隐私边界？", "",
                PlanQuestionType.MULTIPLE_CHOICE, List.of(known, unknown), List.of(), true);
        assertThat(filter.filter(List.of(question), input(COMPLETE, SOURCE))).containsExactly(question);
    }

    @Test
    void shouldKeepCompositeCoverageWhenARequiredInputRuleIsNotKnown() {
        var cancel = new PlanOption("cancel", "取消", "", "覆盖取消保持原值。", false);
        var missing = new PlanOption("missing", "缺项处理", "", "覆盖姓名或身份证号缺项不查询。", false);
        var question = new PlanQuestion("coverage", "本次补测试需要覆盖哪些分支？", "",
                PlanQuestionType.MULTIPLE_CHOICE, List.of(cancel, missing), List.of(), true);
        assertThat(filter.filter(List.of(question), input(COMPLETE, SOURCE))).containsExactly(question);
    }

    @Test
    void shouldKeepRecoveryDecisionsWithoutReopeningTheDetailPrerequisite() {
        var known = free("detail-fill", "查询详情失败时，是否仍按已确认的填充规则写入？");
        var retry = free("detail-retry", "查询详情失败时，最多重试多少次？");
        var timeout = free("detail-timeout", "查询详情失败后的超时上限是多少？");
        assertThat(filter.filter(List.of(known, retry, timeout), input(COMPLETE, SOURCE)))
                .containsExactly(retry, timeout);
        var policy = PlanningDecisionPolicy.from(input(COMPLETE, SOURCE));
        assertThatThrownBy(() -> policy.validateCandidate("详情查询失败时，用列表返回的地址摘要填充。", "questions.options.answer"))
                .isInstanceOf(ProviderResponseValidationException.class);
        policy.validateCandidate("详情失败时不得用列表摘要填充。", "questions.options.answer");
    }

    @Test
    void shouldKeepIndependentNewConditionsWhenExistingExecutionRulesAreKnown() {
        var questions = List.of(
                free("rollback", "用户取消自动填充后，是否回滚后端已提交的事务？"),
                free("redaction", "自动填充基本信息中，敏感字段的脱敏方式如何选择？"),
                free("date-empty", "调查日期为空或无效时，应如何确定排序位置？"),
                free("retry", "接口异常后最多重试多少次？"),
                free("cache", "接口异常后，是否允许用缓存中的旧数据继续自动填充？"),
                free("other-form", "退款表单用户取消时是否仍写入部分字段？"));
        assertThat(filter.filter(questions, input(COMPLETE, SOURCE))).containsExactlyElementsOf(questions);
        var otherConstant = free("other-constant-value", "NEW_FILL_FIELDS 的当前值为 null 时，是否只填空值字段？");
        assertThat(filter.filter(List.of(otherConstant), input(COMPLETE, SOURCE))).containsExactly(otherConstant);
        var cancel = free("cancel-new", "用户取消自动填充时，是否保持表单原值？");
        assertThat(filter.filter(List.of(cancel), input(COMPLETE + "本次取消行为尚未确定，需要用户选择。", SOURCE)))
                .containsExactly(cancel);
        var value = free("value-new", "自动填充时，是否只对当前为 null 或空字符串的字段写入？");
        assertThat(filter.filter(List.of(value), input(COMPLETE + "本次需要修改填充条件，是否覆盖已有值尚未确定。", SOURCE)))
                .containsExactly(value);
        var combined = free("combined", "自动填充范围是否限定为 BASELINE_FILL_FIELDS 中当前为 null 的字段？");
        assertThat(filter.filter(List.of(combined), input(COMPLETE, SOURCE,
                "[PROJECT_SOURCE] src/other.ts：const BASELINE_FILL_FIELDS = ['phone'];"))).containsExactly(combined);
        var otherNamed = free("other-named", "退款表单自动填充基本信息应包含哪些项目？");
        assertThat(filter.filter(List.of(otherNamed), input(COMPLETE + "另需设计退款表单的字段。", SOURCE)))
                .containsExactly(otherNamed);
    }

    @Test
    void shouldReuseTheDeclaredResearchStatusButKeepMethodParametersAndDefinitions() {
        String raw = "为论文准备中文方法提纲，研究Plan问答对提示词质量的影响。当前只拟定研究，尚未实施。"
                + "另设两组获得同样确认信息的公平对照。具体检验依据未来数据分布选择，不预设必然显著。";
        var status = free("status", "方法部分是否需要明确写出研究尚未实施、当前仅为设计的声明？");
        var method = free("method", "方法部分是否需要说明未来数据分布未知、检验方法将根据实际数据选择？");
        var parity = free("parity", "方法部分是否需要说明两组公平对照的具体设置（如获得同样确认信息）？");
        var scores = free("scores", "方法部分是否需要描述两名独立评审盲评的具体流程（如评分维度、一致性报告方式）？");
        var definition = free("definition", "方法部分是否需要包含对Plan问答的操作性定义或示例？");
        var distribution = free("distribution", "未来数据的具体分布类型和检验阈值如何确定？");
        var random = free("random", "方法部分是否需要说明公平对照的随机分配方式？");
        assertThat(filter.filter(List.of(status, method, parity, scores, definition, distribution, random), input(raw)))
                .containsExactly(scores, definition, distribution, random);
    }

    @Test
    void shouldReuseTheDeclaredRegionRootAndDelegateAnExistingUiEvent() {
        String raw = "完善基线匹配，按当前用户所属地区匹配患者基线；" + COMPLETE;
        var event = free("ui-event", "“当前地区有时提示用户是否自动填充基本信息”中的提示，应在什么时机出现？");
        var root = free("root", "“当前地区包含下级地区、排除其他同级地区”中的地区范围，以什么为准？");
        var delay = free("delay", "自动填充提示应延迟多少毫秒出现？");
        var source = free("region-source", "当前用户所属地区的数据来源与缺失处理是什么？");
        assertThat(filter.filter(List.of(event, root, delay, source), input(raw, SOURCE,
                "[PROJECT_DOCUMENT] docs/基线.md：服务端负责地区范围过滤。")))
                .containsExactly(delay, source);
        assertThat(filter.filter(List.of(root), input(raw + "本次地区范围尚未确定，需要用户选择。", SOURCE)))
                .containsExactly(root);
    }

    @Test
    void shouldKeepFieldChoicesThatTheUserExplicitlyReopens() {
        var question = fieldChoice("自动填充时，BASELINE_FILL_FIELDS 中的字段是否全部填充？");
        for (String raw : List.of(
                "完善基线匹配，使用 BASELINE_FILL_FIELDS，但本次字段集合尚未确定，需要用户选择。",
                "现有 BASELINE_FILL_FIELDS 包含地址，计划调整自动填充字段范围，是否保留地址未定。",
                "先查询详情，再填 BASELINE_FILL_FIELDS，但只使用哪部分字段还没决定。")) {
            assertThat(filter.filter(List.of(question), input(raw, SOURCE))).containsExactly(question);
        }
    }

    @Test
    void shouldKeepUnknownFieldRulesDifferentSubjectsAndConflictingDeclarations() {
        var scope = fieldChoice("自动填充时，BASELINE_FILL_FIELDS 中的字段是否全部填充？");
        var unknownValue = free("value", "自动填充字段中 null 值应如何处理？");
        var privacy = free("privacy", "自动填充中敏感字段的脱敏规则是什么？");
        var other = free("other", "退款表单应填充哪些字段？");
        assertThat(filter.filter(List.of(unknownValue, privacy, other), input(COMPLETE, SOURCE)))
                .containsExactly(unknownValue, privacy, other);
        assertThat(filter.filter(List.of(scope), input("完善基线匹配，尚未决定自动填充字段。", SOURCE)))
                .containsExactly(scope);
        assertThat(filter.filter(List.of(scope), input(COMPLETE, SOURCE,
                "[PROJECT_SOURCE] src/other.ts：const BASELINE_FILL_FIELDS = ['name', 'phone'];")))
                .containsExactly(scope);
        assertThat(filter.filter(List.of(scope), input(COMPLETE, SOURCE.replace("PROJECT_SOURCE", "TEST_FIXTURE"))))
                .containsExactly(scope);
    }

    @Test
    void shouldReuseTheKnownTriggerWithoutHidingANewTimingOrDifferentAction() {
        var trigger = free("trigger", "当前地区有匹配记录时，提示用户是否自动填充基本信息的触发时机是什么？");
        var debounce = free("debounce", "自动填充提示需要延迟多少毫秒，以避免连续请求？");
        var other = free("other-trigger", "支付成功后何时发送退款通知？");
        assertThat(filter.filter(List.of(trigger, debounce, other), input(COMPLETE, SOURCE)))
                .containsExactly(debounce, other);
        assertThat(filter.filter(List.of(trigger), input("完善基线自动填充，触发时机尚未确定。", SOURCE)))
                .containsExactly(trigger);
    }

    @Test
    void shouldDelegateOnlyExistingImplementationLookupsAndKeepScopeOrMethodChoices() {
        var implementation = free("impl", "“当前地区包含下级地区但排除其他同级地区”具体如何判定？");
        var business = free("business", "地区范围是否包含下级地区，还是仅限本级？");
        var indices = free("indices", "地区匹配是否需要新建索引并迁移数据库？");
        var tests = testLayerChoice("补测试的范围是什么？");
        var acceptance = free("acceptance", "性能验收要求的响应时间上限是多少？");
        String placement = "[PROJECT_DOCUMENT] docs/基线匹配现状.md：服务端负责地区范围过滤。";
        assertThat(filter.filter(List.of(implementation, tests, indices, acceptance), input(COMPLETE, SOURCE, placement)))
                .containsExactly(indices, acceptance);
        assertThat(filter.filter(List.of(business), input("完善基线匹配，是否包含下级地区尚未确定。", SOURCE, placement)))
                .containsExactly(business);
        assertThat(filter.filter(List.of(tests), input("设计项目性能与测试策略，需要用户决定测试层和成本。", SOURCE)))
                .containsExactly(tests);
    }

    @Test
    void shouldKeepResearchMethodsAndDataDefinitionsWithoutASoftwareDelegationShortcut() {
        var method = free("method", "地区范围与抽样方法如何判定？");
        var tests = testLayerChoice("研究中的测试条件范围是什么？");
        var denominator = free("denominator", "完成率的分母采用哪个口径？");
        assertThat(filter.filter(List.of(method, tests, denominator), input(
                "撰写研究方法提纲，抽样和测试条件尚未确定，完成率分母未定。", SOURCE)))
                .containsExactly(method, tests, denominator);
    }

    @Test
    void shouldKeepExplicitlyRequestedImplementationAndTestLayerDecisions() {
        var implementation = free("impl", "地区范围判定具体如何实现？");
        var tests = testLayerChoice("补测试的范围是什么？");
        String placement = "[PROJECT_DOCUMENT] docs/基线.md：服务端负责地区范围过滤。";
        assertThat(filter.filter(List.of(implementation), input(COMPLETE
                + "本次需要用户选择地区范围判定算法，不直接沿用旧实现。", SOURCE, placement)))
                .containsExactly(implementation);
        assertThat(filter.filter(List.of(tests), input(COMPLETE
                + "这次希望用户自行选择单元测试或端到端测试。", SOURCE)))
                .containsExactly(tests);
    }

    @Test
    void shouldKeepANewFieldConstantAndAConflictingResponsibility() {
        var otherFields = free("other-constant", "自动填充时，NEW_FILL_FIELDS 中的字段是否全部填充？");
        var implementation = free("impl", "地区范围判定具体如何实现？");
        assertThat(filter.filter(List.of(otherFields), input(COMPLETE, SOURCE))).containsExactly(otherFields);
        assertThat(filter.filter(List.of(implementation), input(COMPLETE, SOURCE,
                "[PROJECT_DOCUMENT] docs/server.md：服务端负责地区范围过滤。",
                "[PROJECT_DOCUMENT] docs/client.md：前端负责地区范围过滤。")))
                .containsExactly(implementation);
    }

    private PlanQuestion fieldChoice(String text) {
        return new PlanQuestion("fields", text, "确认业务字段集合。", PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("all", "使用完整字段集合", "保留已经指定的字段。", "使用所有 BASELINE_FILL_FIELDS 字段。", false),
                new PlanOption("some", "仅使用部分字段", "选择需要的部分字段。", "只填充部分 BASELINE_FILL_FIELDS 字段。", false)),
                List.of(), true);
    }

    private PlanQuestion testLayerChoice(String text) {
        return new PlanQuestion("tests", text, "确认测试层。", PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("unit", "单元测试", "覆盖核心逻辑。", "补单元测试。", false),
                new PlanOption("e2e", "端到端测试", "覆盖现有流程。", "补端到端测试。", false)), List.of(), true);
    }

    private PlanQuestion free(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    private PlanningProviderRequest input(String raw, String... summaries) {
        var digest = new PlanningContextDigest("", List.of("Vue 3", "Spring Boot 3"), List.of(), List.of(),
                List.of(summaries), "COMPLETE", summaries.length, List.of());
        return new PlanningProviderRequest(raw, "", List.of(), digest);
    }
}
