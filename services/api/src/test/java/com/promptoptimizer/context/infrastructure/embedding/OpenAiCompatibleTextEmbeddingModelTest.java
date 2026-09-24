package com.promptoptimizer.context.infrastructure.embedding;

import com.promptoptimizer.context.application.TextEmbeddingModel;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证 OpenAI 兼容向量请求、鉴权头和乱序响应归一化。
 */
class OpenAiCompatibleTextEmbeddingModelTest {

    private static final String ENDPOINT = "https://embedding.example.com/v1/embeddings";
    private static final String API_KEY = "test-embedding-key";

    private MockRestServiceServer server;
    private OpenAiCompatibleTextEmbeddingModel model;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SemanticRetrievalProperties properties = new SemanticRetrievalProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey(API_KEY);
        properties.setModel("test-embedding-model");
        model = new OpenAiCompatibleTextEmbeddingModel(builder.build(), properties);
    }

    @Test
    void shouldSendBatchRequestAndRestoreResponseOrder() {
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("test-embedding-model"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(
                """
                                {
                                  "model": "resolved-embedding-model",
                                  "usage": {"prompt_tokens": 12, "total_tokens": 12},
                                  "data": [
                                    {"index": 1, "embedding": [0.0, 1.0]},
                                    {"index": 0, "embedding": [1.0, 0.0]}
                                  ]
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        Logger callLogger = (Logger) LoggerFactory.getLogger(com.promptoptimizer.common.logging.ModelCallLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        callLogger.addAppender(appender);
        TextEmbeddingModel.EmbeddingBatch result;
        try {
            result = model.embed(List.of("第一段", "第二段"));
        } finally {
            callLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(result.model()).isEqualTo("resolved-embedding-model");
        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).containsExactly(1F, 0F);
        assertThat(result.vectors().get(1)).containsExactly(0F, 1F);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("event=model.call.completed")
                        .contains("operation=context.embedding")
                        .contains("resolvedModelId=test-embedding-model")
                        .contains("inputTokens=12")
                        .contains("totalTokens=12")
                        .doesNotContain(API_KEY, ENDPOINT));
        server.verify();
    }
}
