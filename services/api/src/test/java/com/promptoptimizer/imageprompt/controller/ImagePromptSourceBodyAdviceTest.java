package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.TextUpload;
import com.promptoptimizer.imageprompt.service.ImagePromptException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

/** 反序列化前的字节边界，包含无长度流与转义JSON；不创建超大业务对象或调用模型。 */
class ImagePromptSourceBodyAdviceTest {
    private final ImagePromptSourceBodyAdvice advice = new ImagePromptSourceBodyAdvice();

    @Test void declaredOversizeIsRejectedBeforeReadingBytes() {
        var headers = new HttpHeaders(); headers.setContentLength(ImagePromptSourceBodyAdvice.MAX_JSON_BYTES + 1L);
        var body = message(headers, new InputStream() {
            @Override public int read() { throw new AssertionError("超限声明不应读取请求体"); }
        });
        assertThatThrownBy(() -> read(body)).isInstanceOf(ImagePromptException.class)
                .satisfies(failure -> assertThat(((ImagePromptException) failure).status()).isEqualTo(413));
    }

    @Test void absentOrUnderstatedLengthStillChecksActualStreamWithoutReadingBeyondLimit() {
        for (long declared : new long[]{-1, 3}) {
            var headers = new HttpHeaders(); if (declared >= 0) headers.setContentLength(declared);
            var stream = new GeneratedStream(ImagePromptSourceBodyAdvice.MAX_JSON_BYTES + 1024);
            assertThatThrownBy(() -> read(message(headers, stream))).isInstanceOf(ImagePromptException.class)
                    .satisfies(failure -> assertThat(((ImagePromptException) failure).status()).isEqualTo(413));
            assertThat(stream.readBytes).isEqualTo(ImagePromptSourceBodyAdvice.MAX_JSON_BYTES + 1);
        }
    }

    @Test void unicodeEscapesAndJsonStructureReachTheExistingConverterUnchanged() throws Exception {
        String json = "{\"name\":\"资料\",\"entries\":[{\"path\":\"readme.txt\",\"content\":\"" + "\\u4e2d".repeat(1000) + "\"}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var headers = new HttpHeaders(); headers.setContentLength(bytes.length);
        var prepared = read(message(headers, new ByteArrayInputStream(bytes)));
        var result = (TextUpload) new MappingJackson2HttpMessageConverter().read(TextUpload.class, prepared);
        assertThat(result.entries().getFirst().content()).isEqualTo("中".repeat(1000));
    }

    private HttpInputMessage read(HttpInputMessage message) throws Exception {
        var method = ImagePromptSourceController.class.getMethod("text", TextUpload.class, jakarta.servlet.http.HttpServletRequest.class);
        var parameter = new MethodParameter(method, 0);
        return advice.beforeBodyRead(message, parameter, TextUpload.class, MappingJackson2HttpMessageConverter.class);
    }

    private HttpInputMessage message(HttpHeaders headers, InputStream stream) {
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return stream; }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

    /** 无限请求的有界替身，只生成实际请求的字节，不先分配整份恶意JSON。 */
    private static final class GeneratedStream extends InputStream {
        private int remaining;
        private int readBytes;
        private GeneratedStream(int bytes) { remaining = bytes; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; readBytes++; return 32; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int count = Math.min(length, remaining); Arrays.fill(bytes, offset, offset + count, (byte) 32);
            remaining -= count; readBytes += count; return count;
        }
    }
}
