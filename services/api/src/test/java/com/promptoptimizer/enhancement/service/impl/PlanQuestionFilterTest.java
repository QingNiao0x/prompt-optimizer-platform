package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PlanQuestionFilterTest {
    private final PlanQuestionFilter filter = new PlanQuestionFilter();

    @Test
    void shouldAnalyzeAlreadyRequestedWindowTradeoffsWithoutAskingTheUserToSelectAnalysisDimensions() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var source = getClass().getResourceAsStream("/enhancement/approved-window-tradeoff-actual.json")) {
            var fixture = mapper.readTree(source);
            var questions = mapper.convertValue(fixture.get("questions"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<PlanQuestion>>() { });
            assertThat(filter.filter(questions, input(fixture.get("rawPrompt").asText())))
                    .extracting(PlanQuestion::id).containsExactly("window_choice", "threshold_handling");
        }
    }

    @Test
    void shouldKeepNewBusinessDecisionsAndExplicitUserControlInsideAWindowTradeoffQuestion() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var source = getClass().getResourceAsStream("/enhancement/approved-window-tradeoff-actual.json")) {
            var fixture = mapper.readTree(source);
            var questions = mapper.convertValue(fixture.get("questions"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<PlanQuestion>>() { });
            String raw = fixture.get("rawPrompt").asText();
            var question = questions.getLast();
            assertThat(filter.filter(List.of(question), input(raw + "请先让我选择主要取舍的比较维度。")))
                    .containsExactly(question);
            for (String detail : List.of("丙院2026年的观察窗口需另行审批。", "实际比较分母尚未确定。",
                    "是否允许跨院共享患者记录尚未确定。", "资源投入上限为10万元，是否接受？")) {
                var changed = new PlanQuestion(question.id(), question.question(), detail, question.type(),
                        question.options(), question.examples(), true);
                assertThat(filter.filter(List.of(changed), input(raw))).as(detail).containsExactly(changed);
            }
            var original = question.options().getFirst();
            for (String detail : List.of("本次观察窗口统一采用48小时。", "新增退款审批的取舍。")) {
                var changed = new PlanQuestion(question.id(), question.question(), question.hint(), question.type(),
                        List.of(new PlanOption(original.id(), original.label(), original.description(),
                                original.answer() + detail, false, "")), List.of(), true);
                assertThat(filter.filter(List.of(changed), input(raw))).as(detail).containsExactly(changed);
            }
            var unsupportedRecommendation = new PlanQuestion(question.id(), question.question(), question.hint(), question.type(),
                    List.of(new PlanOption(original.id(), original.label(), original.description(), original.answer(), true,
                            "48小时已获批。")), List.of(), true);
            assertThat(filter.filter(List.of(unsupportedRecommendation), input(raw))).containsExactly(unsupportedRecommendation);
            var newExample = new PlanQuestion(question.id(), question.question(), question.hint(), question.type(),
                    question.options(), List.of("还需决定临床评分标准。"), true);
            assertThat(filter.filter(List.of(newExample), input(raw))).containsExactly(newExample);
        }
    }

    @Test
    void shouldDelegateRoutineTestLayersWithoutHidingNewBusinessBranches() {
        String raw = "制定表单补值实现方案，交付按业务行为组织的测试表。匹配使用姓名与证件号两个键，并且记录必须符合当前用户所属地区条件。"
                + "候选按更新时间降序，时间相同按记录编号升序。字段白名单只有电话。"
                + "只有目标值为 null 或空字符串时允许补入来源值。用户点击取消必须保持全部字段原值不变。"
                + "查询或详情失败时保留原值并显示可理解的失败提示。有候选时必须询问用户。"
                + "用户修改这两个键后，之前的候选必须失效。迟到的旧请求必须失效。";
        var layer = new PlanQuestion("layer", "测试清单需要覆盖到哪一层？",
                "原文要求按业务行为组织测试表，但未说明是否包含接口层与前端交互层；这会影响交付的测试清单范围。", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("service", "仅服务层业务行为", "测试清单聚焦匹配与异常分支。",
                                "测试清单聚焦共用补值服务的业务行为：匹配条件、地区过滤、排序、白名单写入、null/空字符串判断、取消与异常分支。", false, ""),
                        new PlanOption("api", "服务层加接口层", "在服务层基础上补充查询接口与详情接口的行为测试。",
                                "测试清单覆盖共用补值服务业务行为，并补充候选查询接口与详情接口的行为测试，包括失败提示。", false, ""),
                        new PlanOption("full", "服务层、接口层与前端交互", "再补充前端确认、取消、迟到请求失效等交互测试。",
                                "测试清单覆盖共用补值服务、候选查询与详情接口，以及前端确认、取消、修改身份键后旧候选失效、迟到请求失效等交互行为。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(layer), input(raw))).isEmpty();
        var added = new PlanQuestion(layer.id(), layer.question(), "", layer.type(),
                List.of(new PlanOption("service", "仅服务层业务行为", "新增退款权限测试。",
                        layer.options().getFirst().answer(), false, "")), List.of(), true);
        assertThat(filter.filter(List.of(added), input(raw))).containsExactly(added);
        assertThat(filter.filter(List.of(layer), input(raw + "请让我选择测试层级。"))).containsExactly(layer);
        var threshold = new PlanQuestion(layer.id(), layer.question(), "响应不得超过 50 毫秒。", layer.type(),
                layer.options(), List.of(), true);
        assertThat(filter.filter(List.of(threshold), input(raw))).containsExactly(threshold);
    }

    @Test
    void shouldKeepNewMissingRegionBehaviorInsideAnOtherwiseRoutineTestLayer() {
        var layer = new PlanQuestion("layer", "测试清单需要覆盖到哪一层？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("service", "仅服务层业务行为", "",
                        "测试清单覆盖地区缺失时自动补全的行为。", false, "")), List.of(), true);
        assertThat(filter.filter(List.of(layer), input("制定表单补值实现方案，交付测试清单，地区必须匹配。")))
                .containsExactly(layer);
    }

    @Test
    void shouldDelegateOnlyTestOptionsWhoseEveryBranchIsAlreadyRequired() {
        String raw = "制定表单补值实现方案，交付测试清单。匹配使用姓名与证件号两个键，并且记录必须符合当前用户所属地区条件。"
                + "候选按更新时间降序，时间相同按记录编号升序。字段白名单只有电话。"
                + "只有目标值为 null 或空字符串时允许补入来源值。";
        var scope = new PlanQuestion("scope", "测试清单需要覆盖哪些范围？", "", PlanQuestionType.MULTIPLE_CHOICE,
                List.of(new PlanOption("backend", "后端匹配与补值逻辑", "", "测试清单覆盖后端匹配与补值逻辑：地区条件、姓名与证件号双键匹配、排序、字段白名单、仅 null 或空字符串可补值。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(scope), input(raw))).isEmpty();
        var added = new PlanQuestion(scope.id(), scope.question(), "", scope.type(),
                List.of(new PlanOption("backend", "后端匹配与补值逻辑", "", "测试清单覆盖后端匹配与补值逻辑：地区条件、退款权限。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(added), input(raw))).containsExactly(added);
        assertThat(filter.filter(List.of(scope), input(raw + "请先询问我测试范围。"))).containsExactly(scope);
    }

    @Test
    void shouldKeepNewTestRequirementsInLabelsDescriptionsAndQuestionReasons() {
        String raw = "制定表单补值实现方案，交付测试清单。匹配使用姓名与证件号两个键，并且记录必须符合当前用户所属地区条件。";
        String answer = "测试清单覆盖后端匹配与补值逻辑：地区条件、姓名与证件号双键匹配。";
        var options = List.of(
                new PlanOption("label", "后端匹配与补值逻辑及退款权限", "", answer, false, ""),
                new PlanOption("description", "后端匹配与补值逻辑", "还需决定退款权限的测试范围。", answer, false, ""),
                new PlanOption("reason", "后端匹配与补值逻辑", "", answer, true, "推荐同时增加退款权限测试。"));
        for (var option : options) {
            var question = new PlanQuestion(option.id(), "测试清单需要覆盖哪些范围？", "", PlanQuestionType.MULTIPLE_CHOICE,
                    List.of(option), List.of(), true);
            assertThat(filter.filter(List.of(question), input(raw))).containsExactly(question);
        }
        var question = new PlanQuestion("question-reason", "测试清单需要覆盖哪些范围？", "还需确认退款权限。",
                PlanQuestionType.MULTIPLE_CHOICE,
                List.of(new PlanOption("backend", "后端匹配与补值逻辑", "", answer, false, "")), List.of(), true);
        assertThat(filter.filter(List.of(question), input(raw))).containsExactly(question);
    }

    @Test
    void shouldDelegatePseudocodeGranularityButKeepExplicitPreferencesAndNewRequirements() {
        var grain = new PlanQuestion("grain", "伪代码需要详细到什么程度？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("flow", "流程级伪代码", "描述步骤", "采用流程级描述。", false, ""),
                        new PlanOption("method", "方法级伪代码", "服务方法", "细化到服务方法、参数与返回结构。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(grain), input("请制定实现方案，交付关键伪代码，不修改项目。"))).isEmpty();
        assertThat(filter.filter(List.of(grain), input("请制定实现方案，先询问我伪代码粒度，再交付关键伪代码。")))
                .containsExactly(grain);
        var extra = new PlanQuestion("extra", grain.question(), "", grain.type(),
                List.of(new PlanOption("flow", "流程级伪代码", "", "覆盖流程，并新增跨租户访问策略。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(extra), input("请制定实现方案，交付关键伪代码，不修改项目。")))
                .containsExactly(extra);
    }

    @Test
    void shouldNotReaskTheConfirmationPresentationAlreadyNamedByTheUser() {
        var presentation = question("presentation", "有匹配候选时，确认交互采用哪种形式？");
        var timing = question("timing", "有匹配候选时，确认是否需要逐字段进行？");
        String raw = "请制定表单补值实现方案，无候选时不弹确认框，有候选时必须询问用户。";
        assertThat(filter.filter(List.of(presentation, timing), input(raw))).containsExactly(timing);
        assertThat(filter.filter(List.of(presentation), input("制定表单补值实现方案，确认形式尚未确定。")))
                .containsExactly(presentation);
    }

    @Test
    void shouldDelegateRoutineOutlineLayoutButKeepResearchDecisionsAndExplicitLayoutPreferences() {
        var layout = question("layout", "方法提纲的章节应如何组织？");
        var sample = question("sample", "研究样本应来自哪些机构？");
        assertThat(filter.filter(List.of(layout, sample), input("撰写论文方法提纲，比较两种提示词方式的质量。")))
                .containsExactly(sample);
        assertThat(filter.filter(List.of(layout), input("撰写论文方法提纲，先询问我章节结构再组织正文。")))
                .containsExactly(layout);
    }

    @Test
    void shouldDelegateAlreadyRequestedComparisonPresentationButKeepNewLegalDecision() {
        String raw = "对两份押金协议逐项并列展示条款差异，保留版本和来源；不替用户判断哪个版本有效。";
        var layout = new PlanQuestion("layout", "押金条款差异采用什么形式呈现？", "",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("table", "并列表格", "", "把已有差异并列成表。", false, ""),
                new PlanOption("text", "分段说明", "", "分段说明已有差异。", false, "")), List.of(), true);
        var scope = question("scope", "押金条款差异适用哪个合同版本？");
        assertThat(filter.filter(List.of(layout, scope), input(raw))).containsExactly(scope);
        assertThat(filter.filter(List.of(layout), input(raw + "请先让我选择差异呈现形式。")))
                .containsExactly(layout);
        var newChoice = new PlanQuestion("new", layout.question(), "", layout.type(),
                List.of(new PlanOption("table", "并列表格", "", "新增跨地区押金适用范围。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(newChoice), input(raw))).containsExactly(newChoice);
        var versionChoice = new PlanQuestion("version", layout.question(), "", layout.type(),
                List.of(new PlanOption("table", "并列表格", "", "确认A版本已经生效后再并列展示。", false, "")),
                List.of(), true);
        assertThat(filter.filter(List.of(versionChoice), input(raw))).containsExactly(versionChoice);
    }

    @Test
    void shouldKeepIndependentBusinessDecisionsRegardlessOfQuestionOrder() {
        var approval = question("approval", "审批规则采用什么标准？");
        var refund = question("refund", "退款规则采用什么标准？");
        assertThat(filter.filter(List.of(approval, refund), input("完善审批与退款流程")))
                .containsExactly(approval, refund);
        assertThat(filter.filter(List.of(refund, approval), input("完善审批与退款流程")))
                .containsExactly(refund, approval);
    }

    @Test
    void shouldOnlyMergeTheSameScopedDecisionWithoutLosingNewConditions() {
        var approval = question("approval", "审批规则采用什么标准？");
        var paraphrase = question("approval-again", "审批规则的标准是什么？");
        var emergency = question("emergency", "紧急订单的审批规则采用什么标准？");
        var refund = question("refund", "退款规则采用什么标准？");
        assertThat(filter.filter(List.of(approval, paraphrase, emergency, refund), input("完善订单流程")))
                .containsExactly(approval, emergency, refund);
    }

    @Test
    void shouldKeepDifferentFileFormatsAndPopulationDefinitions() {
        var data = question("data", "研究数据采用什么文件格式？");
        var deliverable = question("deliverable", "输出文件采用什么格式？");
        var age = question("age", "年龄组按什么标准划分？");
        var urban = question("urban", "城乡人群按什么标准划分？");
        assertThat(filter.filter(List.of(data, deliverable, age, urban), input("设计研究分析方案")))
                .containsExactly(data, deliverable, age, urban);
    }

    @Test
    void shouldRecognizeTheRealDetailFailureParaphraseWithoutHidingASeparateRetryChoice() {
        var failure = question("detail_failure_behavior", "先查询详情这一步失败时，应如何处理？");
        var retry = question("retry", "先查询详情这一步失败时，是否重试三次再提醒用户？");
        assertThat(filter.filter(List.of(failure, retry), input("接口异常提醒但不阻断手工录入。")))
                .containsExactly(retry);
        assertThat(filter.filter(List.of(failure), input("仅列表接口异常提醒但不阻断手工录入。")))
                .containsExactly(failure);
    }

    @Test
    void shouldReuseExplicitDeliveryAndGenericFailureRulesWithoutGuessingNewDetails() {
        var delivery = question("delivery", "本次是否需要输出实际分析结果？");
        assertThat(filter.filter(List.of(delivery), input("只提供分析方案与 R 代码框架，不计算真实结果。"))).isEmpty();
        assertThat(filter.filter(List.of(delivery), input("设计数据分析任务。"))).hasSize(1);
        var failure = question("failure", "查询详情失败时如何处理？");
        assertThat(filter.filter(List.of(failure), input("完善表单，接口异常提醒但不阻断手工录入。"))).isEmpty();
        assertThat(filter.filter(List.of(failure), input("仅列表接口异常提醒但不阻断手工录入。"))).hasSize(1);
        assertThat(filter.filter(List.of(question("timeout", "详情接口超时应该重试几次？")),
                input("接口异常提醒但不阻断手工录入。"))).hasSize(1);
        assertThat(filter.filter(List.of(question("sample", "是否允许提供合成数据验证框架？")),
                input("只提供分析方案与代码框架，不计算真实结果。"))).hasSize(1);
    }

    @Test
    void shouldNotReaskExplicitWritingLanguageOrConfuseSourceLanguage() {
        var language = question("language", "最终方法提纲和提示词使用中文还是中英双语？");
        assertThat(filter.filter(List.of(language), input("中文方法提纲不超过1200字，附表另计。"))).isEmpty();
        assertThat(filter.filter(List.of(language), input("参考材料为中文，输出语言尚未确定。"))).hasSize(1);
        assertThat(filter.filter(List.of(language), input("参考材料是一份中文报告，请分析其中的问题。"))).hasSize(1);
        assertThat(filter.filter(List.of(question("abstract", "摘要是否还需要英文版本？")),
                input("中文方法提纲不超过1200字。"))).hasSize(1);
    }

    @Test
    void shouldOnlyReuseUncontestedRealPlacementEvidence() {
        var known = new PlanningFactCard("placement", PlanningFactCategory.BUSINESS_RULE,
                PlanningFactOrigin.PROJECT_DOCUMENT, "docs/基线.md", "服务端负责地区范围过滤。");
        var prompt = question("placement", "地区范围过滤（含下级、排除同级）应在哪一层实现？");
        for (var origin : List.of(PlanningFactOrigin.PROJECT_DOCUMENT, PlanningFactOrigin.TEST_FIXTURE)) {
            var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 1,
                    List.of(), List.of(new PlanningFactCard(known.id(), known.category(), origin, known.sourcePath(), known.evidence())));
            var result = filter.filter(List.of(prompt), new PlanningProviderRequest("完善基线匹配", "", List.of(), digest));
            assertThat(result).hasSize(origin == PlanningFactOrigin.PROJECT_DOCUMENT ? 0 : 1);
        }
        var conflicting = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 2,
                List.of(), List.of(known, new PlanningFactCard("other", PlanningFactCategory.BUSINESS_RULE,
                "docs/其他.md", "前端负责地区范围过滤。")));
        assertThat(filter.filter(List.of(prompt), new PlanningProviderRequest("完善基线匹配", "", List.of(), conflicting))).hasSize(1);
        var excerpt = new PlanningContextDigest("", List.of(), List.of(), List.of(),
                List.of("[PROJECT_DOCUMENT] docs/基线.md：服务端负责地区范围过滤。列表返回地址摘要。"),
                "COMPLETE", 1, List.of());
        assertThat(filter.filter(List.of(prompt), new PlanningProviderRequest("完善基线匹配", "", List.of(), excerpt))).isEmpty();
        var qualified = question("placement-qualified", "“包含下级地区、排除其他同级地区”的地区范围过滤，应该在哪一层实现？");
        assertThat(filter.filter(List.of(qualified), new PlanningProviderRequest("完善基线匹配，包含下级地区但排除其他同级地区。", "", List.of(), excerpt))).isEmpty();
        assertThat(filter.filter(List.of(question("compound", "地区范围过滤和字段脱敏应该在哪一层实现？")),
                new PlanningProviderRequest("完善基线匹配", "", List.of(), excerpt))).hasSize(1);
    }

    @Test
    void shouldNotReopenAnExplicitlySelectedFieldConstantOrTrustTestArrays() {
        var q = question("fields", "“自动填充基本信息”具体指哪些字段？");
        for (String origin : List.of("PROJECT_SOURCE", "TEST_FIXTURE")) {
            var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(),
                    List.of("[" + origin + "] src/baseline.ts：export const BASELINE_FILL_FIELDS = ['name', 'idNumber', 'residenceAddress'];"),
                    "COMPLETE", 1, List.of());
            assertThat(filter.filter(List.of(q), new PlanningProviderRequest("先查询详情，再填 BASELINE_FILL_FIELDS。", "", List.of(), digest)))
                    .hasSize(origin.equals("PROJECT_SOURCE") ? 0 : 1);
            assertThat(filter.filter(List.of(question("empty", "自动填充字段中 null 值应如何处理？")),
                    new PlanningProviderRequest("先查询详情，再填 BASELINE_FILL_FIELDS。", "", List.of(), digest))).hasSize(1);
        }
    }

    @Test
    void shouldKeepOpenImplementationDetailsOfAnAlreadyDefinedInteractiveMethod() {
        var definition = question("definition", "“Plan”模式在方法部分应如何界定？");
        var rounds = question("rounds", "Plan 模式最多进行几轮问答？");
        assertThat(filter.filter(List.of(definition, rounds), input("研究Plan问答对提示词质量的影响。")))
                .containsExactly(rounds);
        assertThat(filter.filter(List.of(definition), input("研究某种计划策略对提示词的影响，具体机制未确定。")))
                .containsExactly(definition);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "科研死亡率分析；研究范围：广东省|研究地区是哪里？",
            "软件统计任务；编程语言：Python|分析使用哪种编程语言？",
            "教育教案；目标读者：初中学生|目标读者是谁？",
            "商业分析；输出格式：Excel报告|输出采用什么格式？",
            "法律文书；适用法域：中国大陆|适用哪个法域？",
            "运营活动；目标受众：老客户|目标受众是哪些人？"
    })
    void shouldRemoveOnlyQuestionsWhoseFactsAreExplicitAcrossDomains(String prompt, String text) {
        assertThat(filter.filter(List.of(question("one", text)), input(prompt))).isEmpty();
        assertThat(filter.filter(List.of(question("one", text)), input("请协助完成任务"))).hasSize(1);
    }

    @Test
    void shouldKeepConflictsUnknownFactsAndCompoundQuestions() {
        for (String facts : List.of("研究范围：待定", "研究范围：广东省\n研究范围：浙江省")) {
            assertThat(filter.filter(List.of(question("one", "研究地区是哪里？")), input(facts))).hasSize(1);
        }
        assertThat(filter.filter(List.of(question("one", "研究地区和人群分别是什么？")),
                input("研究范围：广东省"))).hasSize(1);
    }

    @Test
    void shouldDeduplicateTextWithoutDependingOnModelQuestionIds() {
        assertThat(filter.filter(List.of(question("one", "研究地区是哪里？"),
                question("two", "研究地区是哪里?")), input("死亡率分析"))).hasSize(1);
    }

    @Test
    void shouldTrustOnlyUserHistoryAndExplicitSafeDigestFacts() {
        var userHistory = new PlanningProviderRequest("分析死亡率", "",
                List.of(new ConversationMessage("user", "研究范围：广东省")));
        var assistantHistory = new PlanningProviderRequest("分析死亡率", "",
                List.of(new ConversationMessage("assistant", "研究范围：广东省")));
        assertThat(filter.filter(List.of(question("region", "研究地区是哪里？")), userHistory)).isEmpty();
        assertThat(filter.filter(List.of(question("region", "研究地区是哪里？")), assistantHistory)).hasSize(1);
    }

    @Test
    void shouldNotAskKnownFrameworkAndShouldKeepConflictOrUnknownFramework() {
        var digest = new PlanningContextDigest("", List.of("Spring Boot 3"), List.of(),
                List.of(), List.of("pom.xml：订单服务"), "COMPLETE", 1, List.of());
        var known = new PlanningProviderRequest("开发订单接口", "", List.of(), digest);
        var unknown = input("开发订单接口");
        assertThat(filter.filter(List.of(question("framework", "项目使用什么后端框架？")), known)).isEmpty();
        assertThat(filter.filter(List.of(question("framework", "项目使用什么后端框架？")), unknown)).hasSize(1);
        assertThat(filter.filter(List.of(question("migration", "是否更换项目现有后端框架？")), known)).hasSize(1);
    }

    @Test
    void shouldMergeParaphrasesOfSingleKnownDimensionWithoutDroppingCompoundQuestions() {
        assertThat(filter.filter(List.of(
                question("region-a", "研究地区是哪里？"),
                question("region-b", "研究覆盖的地区范围是哪里？"),
                question("region-and-group", "研究地区和人群分别是什么？")
        ), input("分析死亡率"))).extracting("id").containsExactly("region-a", "region-and-group");
    }

    @Test
    void shouldDiscardResearchQuestionIntroducedOnlyByAttachment() {
        var digest = new PlanningContextDigest("", List.of("Spring Boot 3"), List.of(),
                List.of(), List.of("研究方案.txt：2015 至 2025 年死亡率研究"), "COMPLETE", 1, List.of());
        var input = new PlanningProviderRequest("开发订单接口", "", List.of(), digest);
        assertThat(filter.filter(List.of(
                question("research-region", "这项研究具体覆盖哪个地区？"),
                question("order-rule", "订单取消后已支付款项如何处理？")
        ), input)).extracting("id").containsExactly("order-rule");
    }

    @Test
    void shouldUseSourcedFactCardsToRemoveParaphrasedKnownQuestionsButKeepDifferentBusinessDecisions() {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(),
                "COMPLETE", 2, List.of(), List.of(
                new PlanningFactCard("F01", PlanningFactCategory.REGION,
                        "docs/研究方案.txt", "研究范围：广东省"),
                new PlanningFactCard("F02", PlanningFactCategory.DATA_FORMAT,
                        "docs/数据字典.txt", "数据格式：CSV"),
                new PlanningFactCard("F03", PlanningFactCategory.BUSINESS_RULE,
                        "docs/订单审批方案.txt", "订单金额超过五万元时必须由财务复核。")
        ));
        var input = new PlanningProviderRequest("分析广东省数据并实现订单审批", "", List.of(), digest);

        assertThat(filter.filter(List.of(
                question("region-a", "研究地区是哪里？"),
                question("region-b", "本次研究覆盖哪个区域？"),
                question("format", "用户提供的数据是什么格式？"),
                question("approval", "订单金额的审批规则是什么？"),
                question("refund", "订单取消后已支付款项如何退款？")
        ), input)).extracting("id").containsExactly("refund");
    }

    @Test
    void shouldKeepQuestionsWhenFactCardsConflictOrOnlyPartiallyAddressACompoundQuestion() {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(),
                "COMPLETE", 2, List.of(), List.of(
                new PlanningFactCard("F01", PlanningFactCategory.REGION,
                        "docs/a.txt", "研究范围：广东省"),
                new PlanningFactCard("F02", PlanningFactCategory.REGION,
                        "docs/b.txt", "研究范围：浙江省"),
                new PlanningFactCard("F03", PlanningFactCategory.DATA_FORMAT,
                        "docs/data.txt", "数据格式：CSV")
        ));
        var input = new PlanningProviderRequest("分析心脑血管疾病", "", List.of(), digest);

        assertThat(filter.filter(List.of(
                question("region", "研究区域具体是哪一省？"),
                question("compound", "研究数据的来源和格式分别是什么？")
        ), input)).extracting("id").containsExactly("region", "compound");
    }

    @Test
    void shouldNotAskForPathsSchemaOrToolchainVersionsAlreadyPresentInUploadedProject() {
        var digest = new PlanningContextDigest("",
                List.of("Java 21", "Spring Boot 3"),
                List.of("maven:com.baomidou:mybatis-plus-spring-boot3-starter@3.5.17"),
                List.of("services/api/src/main/java/com/promptoptimizer/analytics"),
                List.of(
                        "services/api/src/main/java/com/promptoptimizer/analytics/service/AdminAnalyticsService.java：Java代码文件，主要定义：AdminAnalyticsService",
                        "services/api/src/main/resources/mapper/analytics/AdminAnalyticsMapper.xml：映射 audit_event",
                        "services/api/src/main/resources/db/migration/V1__init_schema.sql：CREATE TABLE audit_event"
                ),
                "COMPLETE", 3, List.of());
        var uploaded = new PlanningProviderRequest(
                "修复统计日志模块 /api/v1/admin/analytics/dashboard 的报错",
                "", List.of(), digest);
        assertThat(filter.filter(List.of(
                question("path", "统计日志模块的后端代码在哪个目录或仓库中？请提供 AdminAnalyticsService 和对应 Mapper 的源码路径或关键代码片段。"),
                question("schema", "统计日志相关的数据库表结构是怎样的？请提供涉及的表名、字段及索引信息。"),
                question("java", "项目使用的 Java 版本是多少？"),
                question("mybatis", "项目使用的 MyBatis 及 MyBatis-Spring 版本是多少？")
        ), uploaded)).isEmpty();
        assertThat(filter.filter(List.of(
                question("path", "统计日志模块的后端代码在哪个目录或仓库中？请提供 AdminAnalyticsService 和对应 Mapper 的源码路径或关键代码片段。"),
                question("java", "项目使用的 Java 版本是多少？")
        ), input("修复统计日志模块的报错"))).hasSize(2);
        assertThat(filter.filter(List.of(question("upgrade", "是否把 MyBatis 升级到更新版本？")), uploaded)).hasSize(1);
    }

    private PlanningProviderRequest input(String text) { return new PlanningProviderRequest(text, "", List.of()); }
    private PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
