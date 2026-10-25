package com.promptoptimizer.trial;

import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.service.impl.*;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import com.promptoptimizer.imageprompt.service.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.service.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实安全链、真实访客账本和HTTP请求验证；模型边界替身不发送真实资料或创建账号。 */
@WebMvcTest(value = TrialController.class, properties = {"app.trial.store-mode=MEMORY", "app.provider.mode=mock", "app.trial.enabled=true"})
@Import({AuthenticatedMvcTestConfiguration.class, RequestIdFilter.class, TrialService.class, TrialStore.class, ClientIpResolver.class})
class TrialControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean NaturalEnhancementService natural;
    @MockBean DefaultEnhancementOrchestrator structured;
    @MockBean PlatformModelCatalog catalog;
    @MockBean ImagePromptService images;
    @MockBean ImagePromptAssetService assets;
    @MockBean ImagePromptModelService imageModels;
    @MockBean TextPolishService polish;
    @MockBean TextPolishModelService polishModels;

    @Test void allFormalHistoryAndPlanPathsRemainPrivate() throws Exception {
        for (String path : List.of("/api/v1/text-polish/history", "/api/v1/image-prompts/history", "/api/v1/optimization-history",
                "/api/v2/optimization-history", "/api/v1/trial/text-polish/history", "/api/v1/trial/image-prompts/history"))
            mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
        for (String path : List.of("/api/v2/optimizations/plan", "/api/v1/image-prompts/plans", "/api/v1/trial/image-prompts/plans",
                "/api/v1/text-polish/operations/" + UUID.randomUUID() + "/save", "/api/v1/image-prompts/operations/" + UUID.randomUUID() + "/save"))
            mvc.perform(post(path).with(anonymous()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        verifyNoInteractions(natural, structured, images, polish, assets);
    }

    @Test void trialSessionAndGenerateRequireCsrfAndServerIssuedCookie() throws Exception {
        mvc.perform(post("/api/v1/trial/session").with(anonymous()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trial/status").with(anonymous()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.initialized").value(false));
        mvc.perform(get("/api/v1/trial/text-polish/capabilities").with(anonymous()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TRIAL_SESSION_REQUIRED"));
        verifyNoInteractions(polish);
    }

    @Test void threeSuccessfulRequestsThenFourthIsRejectedAndReplayDoesNotSpendTwice() throws Exception {
        String cookieHeader = mvc.perform(post("/api/v1/trial/session").with(anonymous()).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        Cookie cookie = new Cookie(TrialService.COOKIE, cookieHeader.substring(cookieHeader.indexOf('=') + 1, cookieHeader.indexOf(';')));
        when(polish.generateTrial(any())).thenAnswer(call -> {
            Generate request = call.getArgument(0);
            return new Document(request.requestId(), request.input(), "测试正文", "测试正文", null, SourceMode.RAW,
                    "mock", "测试模型", true, 0, List.of(), false, false, Instant.now());
        });
        String first = null;
        for (int i = 0; i < 3; i++) {
            String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"input\":{\"sourceText\":\"主动输入的测试正文\"}}";
            if (first == null) first = body;
            mvc.perform(post("/api/v1/trial/text-polish").with(anonymous()).with(csrf()).cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.historySaved").value(false));
        }
        mvc.perform(post("/api/v1/trial/text-polish").with(anonymous()).with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(first)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/trial/text-polish").with(anonymous()).with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"input\":{\"sourceText\":\"第四次\"}}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("TRIAL_QUOTA_EXHAUSTED"));
        mvc.perform(get("/api/v1/trial/status").with(anonymous()).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotas.textPolish.remaining").value(0))
                .andExpect(jsonPath("$.data.quotas.enhancement.remaining").value(3))
                .andExpect(jsonPath("$.data.quotas.imagePrompt.remaining").value(1));
        verify(polish, times(3)).generateTrial(any());
    }
}
