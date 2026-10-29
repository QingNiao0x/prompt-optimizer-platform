package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.imageprompt.service.ImagePromptException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * 资料集合JSON在Jackson分配对象前执行有界读取。只限制新资料集合入口，不改变已有增强或媒体上传协议。
 * 线上JSON预留转义与文件清单开销；解码后的正文仍由资料服务执行更小的5 MiB总量限制。
 * @author QingNiao
 * @since 0.1.0
 */
@ControllerAdvice(assignableTypes = ImagePromptSourceController.class)
public class ImagePromptSourceBodyAdvice extends RequestBodyAdviceAdapter {
    public static final int MAX_JSON_BYTES = 32 * 1024 * 1024;

    /** 只在文本集合反序列化前执行；单文件multipart和其他工作台继续使用各自预算。 */
    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getMethod() != null && parameter.getMethod().getName().equals("text");
    }

    /** 声明长度和实际流双重限制，无Content-Length或声明偏小也不能绕过内存预算。 */
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (message.getHeaders().getContentLength() > MAX_JSON_BYTES) throw tooLarge();
        // 先有界读取，再交给Jackson；异常在反序列化之前产生，避免被转换为普通JSON语法错误。
        byte[] body = message.getBody().readNBytes(MAX_JSON_BYTES + 1);
        if (body.length > MAX_JSON_BYTES) throw tooLarge();
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }

    private ImagePromptException tooLarge() {
        return new ImagePromptException(413, "SOURCE_BODY_TOO_LARGE", "资料集合请求超过32 MiB传输上限，请减少文件数量或分批添加；正文仍受5 MiB限制。");
    }
}
