package com.promptoptimizer.enhancement.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退役选项只兼容旧请求读取；其取值不能进入模型或新历史，其他显式选项保持原值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class EnhancementOptionsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ignoresRetiredExampleOptionWithoutChangingOtherOptions(boolean oldValue) throws Exception {
        var mapper = new ObjectMapper();
        String payload = "{\"templateCode\":\"GENERAL\",\"includeConversationHistory\":false,"
                + "\"includePermissionBoundaries\":true,\"includeExamples\":" + oldValue
                + ",\"promptFormat\":\"COMPACT\"}";

        var options = mapper.readValue(payload, EnhancementOptions.class);
        assertThat(options).isEqualTo(new EnhancementOptions(TemplateCode.GENERAL, false, true, PromptFormat.COMPACT));
        assertThat(mapper.writeValueAsString(options)).doesNotContain("includeExamples");
    }
}
