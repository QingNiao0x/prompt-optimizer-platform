package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.application.DocumentSummaryModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * @Description: 验证 OpenAI 兼容 Map-Reduce 摘要请求和结构化响应解析。
 */
class OpenAiCompatibleDocumentSummaryModelTest {

    private static final String ENDPOINT = "https://model.example.com/chat/completions";
    private static final String API_KEY = "test-summary-key";

    private MockRestServiceServer server;
    private OpenAiCompatibleDocumentSummaryModel model;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey(API_KEY);
        properties.setModel("test-chat-model");
        model = new OpenAiCompatibleDocumentSummaryModel(
                builder.build(),
                new ObjectMapper(),
                properties
        );
    }

    @Test
    void shouldSendStructuredMapRequestAndReadSummary() {
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("test-chat-model"))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("MAP")))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(
                        """
                                {
                                  "model": "resolved-chat-model",
                                  "choices": [
                                    {"message": {"role": "assistant", "content": "{\\\"summary\\\":\\\"批次摘要\\\"}"}}
                                  ]
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        DocumentSummaryModel.SummaryResult result = model.summarize(
                new DocumentSummaryModel.SummaryRequest(
                        DocumentSummaryModel.SummaryStage.MAP,
                        "docs/report.txt",
                        "text",
                        List.of(new DocumentSummaryModel.SummaryPart(0, "第一章", "正文内容")),
                        1_200
                )
        );

        assertThat(result.summary()).isEqualTo("批次摘要");
        assertThat(result.model()).isEqualTo("resolved-chat-model");
        server.verify();
    }
}
