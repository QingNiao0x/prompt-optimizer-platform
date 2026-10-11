package com.promptoptimizer.common.logging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigInteger;

/**
 * 严格接收供应商报告的 Token 整数，禁止 Jackson 把浮点数或字符串悄悄转换为整数。
 * 非法值使用仅进程内的 -1 哨兵标记 INVALID，不阻断本来有效的模型业务响应；落库前剔除负数。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ReportedTokenCountDeserializer extends JsonDeserializer<Long> {
    /** 缺失/null 保持未知；非整数、负数和 long 越界不估算、不四舍五入。 */
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            parser.skipChildren();
            return -1L;
        }
        BigInteger value = parser.getBigIntegerValue();
        return value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0 ? value.longValue() : -1L;
    }
}
