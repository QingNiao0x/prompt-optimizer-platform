package com.promptoptimizer.provider.infrastructure.openai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 通过实际适配器复现无诊断的 Plan 502；错误详情和日志仅暴露代码定义的原因及位置。 */
class PlanFailureDiagnosticsTest {
    @ParameterizedTest
    @CsvSource({
            "json,PLAN_JSON_INVALID,plan.json",
            "missing,PLAN_STRUCTURE_INVALID,plan.questions",
            "type,PLAN_QUESTION_INVALID,questions.type",
            "empty,RESPONSE_EMPTY,response.content",
            "length,RESPONSE_TRUNCATED,response.finishReason"
    })
    void shouldRetainTheSpecificRejectedFieldAfterThreeAttemptsWithoutReturningTheModelBody(
            String example, String expectedReason, String expectedField) throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        String endpoint = "https://model.example.com/chat/completions";
        properties.setEndpoint(URI.create(endpoint));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        var mapper = new ObjectMapper();
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
        String sensitiveMarker = "private-model-body-password=DIAGNOSTIC_ONLY";
        String content = switch (example) {
            case "json" -> sensitiveMarker;
            case "missing" -> "{\"summary\":\"尚未完整\"}";
            case "type" -> "{\"summary\":\"确认研究资料\",\"questions\":[{\"id\":\"q1\",\"question\":\"范围是什么\",\"type\":\"BAD_TYPE\"}]}";
            case "empty" -> "";
            default -> "{\"summary\":\"资料已完整\",\"questions\":[]}";
        };
        String completion = mapper.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "finish_reason", example.equals("length") ? "length" : "stop",
                "message", Map.of("role", "assistant", "content", content)))));
        upstream.expect(times(3), requestTo(endpoint)).andRespond(withSuccess(completion, MediaType.APPLICATION_JSON));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatiblePromptEnhancementProvider.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Throwable failure = catchThrowable(() -> provider.plan(new PlanningProviderRequest(
                    "撰写研究方法提纲，不编造结论。", "", List.of())));
            assertThat(failure).isInstanceOf(ProviderResponseValidationException.class);
            var invalid = (ProviderResponseValidationException) failure;
            assertThat(invalid.getReason().name()).isEqualTo(expectedReason);
            assertThat(invalid.getField()).isEqualTo(expectedField);
            var request = new MockHttpServletRequest("POST", "/api/v1/optimizations/plan");
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "plan-validation-diagnostic-test");
            var response = new GlobalExceptionHandler().handleProviderException(invalid, request);
            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(response.getBody().error().code()).isEqualTo("RESULT_INVALID");
            assertThat(response.getBody().error().details())
                    .containsEntry("validationReason", expectedReason).containsEntry("validationField", expectedField)
                    .containsEntry("modelAttempts", 3);
            String responseText = mapper.writeValueAsString(response.getBody());
            assertThat(responseText).doesNotContain(sensitiveMarker, "BAD_TYPE");
            if (!content.isBlank()) assertThat(responseText).doesNotContain(content);
            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).filteredOn(message -> message.contains("event=model.response.validation_failed"))
                    .hasSize(3).allMatch(message -> message.contains("reason=" + expectedReason)
                            && message.contains("field=" + expectedField));
            assertThat(messages).noneMatch(message -> message.contains(sensitiveMarker));
            assertThat(appender.list).allMatch(event -> event.getThrowableProxy() == null);
            upstream.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
