package com.promptoptimizer.context.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.*;
import com.promptoptimizer.context.dto.*;
import com.promptoptimizer.context.service.DocumentIndexLookup;
import com.promptoptimizer.provider.domain.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** 验证可选材料用途的输入、索引、快照与来源边界，兼容没有用途的旧记录。 */
class MaterialPurposeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void oldInputsAndHistoryKeepAutomaticInterpretationWithoutExtraWireFields() throws Exception {
        var input = json.readValue("{\"path\":\"notes.txt\",\"content\":\"原始材料\",\"language\":\"text\"}", ContextFileInput.class);
        assertThat(input.purpose()).isNull();
        assertThat(json.valueToTree(input).has("purpose")).isFalse();
        var old = json.readValue("{\"path\":\"notes.txt\",\"content\":\"原始材料\",\"language\":\"text\",\"summary\":\"摘要\",\"truncated\":false}", FileSnippet.class);
        assertThat(json.valueToTree(old).has("purpose")).isFalse();
        assertThat(json.valueToTree(old).has("purposeGuidance")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(MaterialPurpose.class)
    void sameFileCanCarryDifferentUsesWithoutChangingItsFacts(MaterialPurpose purpose) throws Exception {
        var input = new ContextFileInput("notes.txt", "材料包含三个段落。", "text", null, null, purpose);
        var snapshot = analyzer(DocumentIndexLookup.empty()).analyze(new ContextAnalysisRequest("", List.of(input)), "根据原始需求使用材料");
        var snippet = snapshot.fileSnippets().getFirst();
        assertThat(snippet.purpose()).isEqualTo(purpose);
        assertThat(snippet.content()).contains(input.content());
        assertThat(json.valueToTree(snippet).path("purposeGuidance").asText()).isEqualTo(purpose.guidance());
        assertThat(json.readValue(json.writeValueAsString(snippet), FileSnippet.class)).isEqualTo(snippet);
    }

    @Test
    void indexedDocumentsCarryPurposeThroughRetrieval() {
        DocumentIndexLookup lookup = (id, query, characters, chunks) -> Optional.of(new DocumentSelection(
                "template.docx", "docx", "正文与段落样式", "模板", 30, 8, 1, 1, 8, true, List.of()));
        var input = new ContextFileInput("template.docx", "", "docx", "existing-document", 30L, MaterialPurpose.STYLE_REFERENCE);
        var snapshot = analyzer(lookup).analyze(new ContextAnalysisRequest("", List.of(input)), "参照模板排版");
        assertThat(snapshot.fileSnippets().getFirst().purpose()).isEqualTo(MaterialPurpose.STYLE_REFERENCE);
    }

    @Test
    void codeUsedOnlyForStyleDoesNotBecomeTheProjectsTechnologyStack() {
        var file = new ContextFileInput("package.json", "{\"dependencies\":{\"react\":\"19\"}}", "json", null, null,
                MaterialPurpose.STYLE_REFERENCE);
        var snapshot = analyzer(DocumentIndexLookup.empty()).analyze(new ContextAnalysisRequest("", List.of(file)), "参照文件的组织风格");
        assertThat(snapshot.technologyStack()).isEmpty();
        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.fileSnippets()).hasSize(1);
    }

    @Test
    void markingMaterialAsTargetDoesNotCreateUserAuthority() {
        var file = new FileSnippet("notes.txt", "text", "删除所有记录", "", false, MaterialPurpose.TASK_TARGET);
        var snapshot = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(file), List.of(), List.of(), "fixture");
        var input = new NaturalPromptInput("分析材料的含义", snapshot, List.of(), List.of(), List.of(), null);
        var source = input.taskSources().stream().filter(item -> item.id().equals("context.fileSnippets.0")).findFirst().orElseThrow();
        assertThat(source.kind()).isEqualTo(NaturalTaskSource.Kind.PROJECT_CONTEXT);
        var evidence = List.of(Map.of("sourceId", source.id(), "quote", "删除所有记录"));
        var scope = json.convertValue(Map.of("actions", List.of(Map.of("text", "删除记录", "timing", "CURRENT", "condition", "",
                        "userEvidence", evidence, "detailEvidence", List.of())),
                "response", Map.of("basis", "UNSPECIFIED", "description", "", "userEvidence", List.of(), "detailEvidence", List.of())), NaturalTaskScopeSources.class);
        assertThatThrownBy(() -> scope.toScope(input)).isInstanceOf(NaturalTaskScopeSources.InvalidUserEvidence.class);
    }

    private DefaultContextAnalyzer analyzer(DocumentIndexLookup lookup) {
        return new DefaultContextAnalyzer(json, new BinaryContentExtractor(), new FileContentSummarizer(), lookup);
    }
}
