package com.promptoptimizer.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 用量只能是明确整数；异常遥测不能改坏本来有效的业务内容。 */
class ReportedTokenCountDeserializerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsOnlyNonnegativeLongAndKeepsNullUnknown() throws Exception {
        assertThat(json.readValue("{\"tokens\":0}", Count.class).tokens()).isZero();
        assertThat(json.readValue("{\"tokens\":9223372036854775807}", Count.class).tokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(json.readValue("{\"tokens\":null}", Count.class).tokens()).isNull();
        assertThat(json.readValue("{}", Count.class).tokens()).isNull();
        for (String value : new String[]{"1.5", "\"10\"", "-1", "9223372036854775808", "{}", "[]"}) {
            assertThat(json.readValue("{\"tokens\":" + value + "}", Count.class).tokens()).isEqualTo(-1L);
        }
    }

    record Count(@JsonDeserialize(using = ReportedTokenCountDeserializer.class) Long tokens) { }
}
