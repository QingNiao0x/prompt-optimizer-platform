package com.promptoptimizer.analytics.dto;

import com.promptoptimizer.analytics.controller.AnalyticsDeliveryExceptionHandler;
import com.promptoptimizer.analytics.controller.AnalyticsEventController;
import com.promptoptimizer.analytics.service.AnalyticsDeliveryUnavailableException;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.analytics.service.AnalyticsIdentityChangedException;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证浏览器事件入口的身份、CSRF、参数校验与恢复错误协议，错误正文不得包含底层详情。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@WebMvcTest(AnalyticsEventController.class)
@Import({RequestIdFilter.class, AuthenticatedMvcTestConfiguration.class, AnalyticsDeliveryExceptionHandler.class})
class AnalyticsEventControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private AnalyticsEventService analyticsEventService;

    @Test
    void anonymousRequestsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/context").with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/analytics/events").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"APP_VISIT\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void ordinaryAccountMayCaptureOwnEventsAndReadNonAuthenticationContext() throws Exception {
        var context = new ClientAnalyticsContext(UUID.randomUUID(), UUID.randomUUID());
        when(analyticsEventService.clientContext(any())).thenReturn(context);
        mockMvc.perform(get("/api/v1/analytics/context").with(user("fixture").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(context.userId().toString()))
                .andExpect(jsonPath("$.data.loginSessionId").value(context.loginSessionId().toString()));
        mockMvc.perform(post("/api/v1/analytics/events").with(user("fixture").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"APP_VISIT\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidCsrfIsForbiddenBeforeCollection() throws Exception {
        mockMvc.perform(post("/api/v1/analytics/events").with(user("fixture").roles("USER"))
                        .with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"APP_VISIT\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void missingTypeUnknownTypeAndMalformedFieldsReturnBadRequest() throws Exception {
        for (String body : new String[]{"{}", "{\"eventType\":\"LOGIN\"}",
                "{\"eventType\":\"APP_VISIT\",\"eventId\":\"invalid\"}",
                "{\"eventType\":\"APP_VISIT\",\"expectedUserId\":\"invalid\"}",
                "{\"eventType\":\"APP_VISIT\",\"expectedLoginSessionId\":\"invalid\"}",
                "{\"eventType\":\"APP_VISIT\",\"occurredAt\":\"2026-10-02\"}"}) {
            mockMvc.perform(post("/api/v1/analytics/events").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void durableReceiptFailureReturnsSanitizedRetryable503() throws Exception {
        doThrow(new AnalyticsDeliveryUnavailableException(new IllegalStateException("private underlying details")))
                .when(analyticsEventService).recordClientEvent(any(ClientAnalyticsEventRequest.class), any());
        mockMvc.perform(post("/api/v1/analytics/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"APP_VISIT\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.code").value("ANALYTICS_DELIVERY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(content().string(not(containsString("private underlying details"))));
    }

    @Test
    void accountChangeReturnsSanitizedNonRetryable409() throws Exception {
        doThrow(new AnalyticsIdentityChangedException()).when(analyticsEventService)
                .recordClientEvent(any(ClientAnalyticsEventRequest.class), any());
        mockMvc.perform(post("/api/v1/analytics/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"APP_VISIT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.code").value("ANALYTICS_IDENTITY_CHANGED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }
}
