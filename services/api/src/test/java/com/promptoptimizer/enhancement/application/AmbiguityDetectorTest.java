package com.promptoptimizer.enhancement.application;

import org.junit.jupiter.api.Test;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmbiguityDetectorTest {

    private final AmbiguityDetector detector = new AmbiguityDetector();

    @Test
    void shouldReportMissingDetailsWhenPromptIsVague() {
        assertThat(detector.detect("弄个排序"))
                .hasSize(2)
                .anyMatch(item -> item.contains("哪类数据排序"))
                .anyMatch(item -> item.contains("哪个字段"));
    }

    @Test
    void shouldAvoidInputAndOutputWarningsWhenPromptIsSpecific() {
        assertThat(detector.detect("接收整数列表作为输入，返回升序列表，并用测试验证重复元素和空列表"))
                .isEmpty();
    }

    @Test
    void shouldNotRequireMagicKeywordsForClearShortOrNonSoftwareTasks() {
        assertThat(detector.detect("将这句话翻译成英文：你好")).isEmpty();
        assertThat(detector.detect("把整数从小到大排序")).isEmpty();
        assertThat(detector.detect("把整数升序排序，不使用快速排序")).isEmpty();
        assertThat(detector.detect("请把上传的会议纪要压缩成三条中文要点，每条不超过30字")).isEmpty();
    }

    @Test
    void shouldReadActualRelevantFileContentInsteadOfOnlySummaryOrDirectory() {
        ContextSnapshot context = context("src/SortService.java", "整数列表按升序排序；重复值保留。");
        assertThat(detector.detect("实现排序功能", context, List.of())).isEmpty();
        assertThat(detector.detect("实现排序功能", context("src/SortService.java", ""), List.of())).hasSize(2);
    }

    @Test
    void shouldUseExistingAuthenticationImplementationButNotJustDependencyName() {
        assertThat(detector.detect("为用户模块添加登录功能",
                context("src/security/LoginService.java", "request.getSession().setAttribute(userId, user);"), List.of()))
                .isEmpty();
        assertThat(detector.detect("为用户模块添加登录功能",
                context("pom.xml", "<artifactId>jwt</artifactId>"), List.of()))
                .singleElement().asString().contains("Spring Boot", "认证与会话方式");
    }

    @Test
    void shouldUseUserConfirmedHistoryAndNotAssistantGuesses() {
        assertThat(detector.detect("实现排序", null,
                List.of(new ConversationMessage("user", "对整数按升序排序")))).isEmpty();
        assertThat(detector.detect("实现排序", null,
                List.of(new ConversationMessage("assistant", "可以对整数按升序排序")))).hasSize(2);
    }

    @Test
    void shouldNotTreatNegatedOrUnrelatedFactsAsResolvedChoices() {
        assertThat(detector.detect("添加登录功能，不使用 JWT", null, List.of())).hasSize(1);
        assertThat(detector.detect("实现排序", context("docs/billing.md", "整数金额按升序展示"), List.of()))
                .hasSize(2);
    }

    @Test
    void shouldAskSpecificResearchQuestionOnlyForUnresolvedMethodChoice() {
        assertThat(detector.detect("分析某地区死亡率及YLL", context("data/研究说明.md",
                "研究范围：广东省\n分析2015至2025年数据"), List.of()))
                .singleElement().asString().contains("参考寿命表");
        assertThat(detector.detect("分析某地区死亡率及YLL", context("data/研究说明.md",
                "研究范围：广东省\n采用WHO标准寿命表计算YLL"), List.of())).isEmpty();
    }

    @Test
    void shouldRejectInvalidPromptWithoutNullPointerOrUnboundedProcessing() {
        for (String input : new String[]{null, " ", "字".repeat(8_001)}) {
            assertThatThrownBy(() -> detector.detect(input)).isInstanceOf(InvalidOptimizationRequestException.class);
        }
        assertThat(detector.detect("字".repeat(8_000))).isEmpty();
    }

    private ContextSnapshot context(String path, String content) {
        return new ContextSnapshot("", List.of(new TechnologyStackItem("Spring Boot", "pom.xml", 1.0)),
                List.of(), List.of(path), List.of(new FileSnippet(path, "text", content, "资料摘要", false)),
                List.of(), List.of(), "test");
    }
}
