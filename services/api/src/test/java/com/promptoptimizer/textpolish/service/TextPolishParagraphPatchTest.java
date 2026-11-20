package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证段号绑定、原稿隔离及段内换行保护。 */
class TextPolishParagraphPatchTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replacesOnlySelectedOccurrenceAndKeepsCrLfWhitespace() throws Exception {
        String source = "标题\r\n\r\n  重复段落。 \r\n \r\n  重复段落。\r\n";
        var patch = new TextPolishParagraphPatch(source);
        assertThat(patch.paragraphs()).extracting(p -> p.get("id")).containsExactly("P1", "P2", "P3");
        assertThat(patch.apply(json.readTree("{\"edits\":[{\"id\":\"P3\",\"text\":\"只改这里。\"}]}")))
                .isEqualTo("标题\r\n\r\n  重复段落。 \r\n \r\n  只改这里。\r\n");
        assertThat(patch.apply(json.readTree("{\"edits\":[]}"))).isEqualTo(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"edits\":[{\"id\":\"P0\",\"text\":\"新稿\"}]}",
            "{\"edits\":[{\"id\":\"P1\",\"text\":\"新稿\"},{\"id\":\"P1\",\"text\":\"再改\"}]}",
            "{\"edits\":[{\"id\":\"P1\",\"text\":\"首行\\n\\n末行\"}]}",
            "{\"edits\":[{\"id\":\"P1\",\"text\":\"改成一行\"}]}",
            "{\"edits\":[{\"id\":\"P1\",\"text\":\" \"}]}",
            "{\"edits\":[],\"polishedText\":\"越界\"}"})
    void refusesUnknownDuplicateBlankAndStructuralChanges(String response) throws Exception {
        var patch = new TextPolishParagraphPatch("首行\n次行\n\n下一段");
        var parsed = json.readTree(response);
        assertThatThrownBy(() -> patch.apply(parsed)).isInstanceOf(IllegalArgumentException.class);
    }
}
