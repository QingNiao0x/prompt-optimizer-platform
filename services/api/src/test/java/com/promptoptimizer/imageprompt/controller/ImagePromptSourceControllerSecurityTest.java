package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.*;
import com.promptoptimizer.imageprompt.service.ImagePromptSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实安全过滤链下的资料入口授权、CSRF与请求验证；不创建账号，不发送文件给模型。 */
@WebMvcTest(ImagePromptSourceController.class)
@Import({RequestIdFilter.class, AuthenticatedMvcTestConfiguration.class})
class ImagePromptSourceControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean ImagePromptSourceService sources;
    @MockBean CurrentActor current;
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");

    @BeforeEach void actor() { when(current.require()).thenReturn(actor); }

    @Test void privateSourcesAndCapabilitiesRejectAnonymousUsers() throws Exception {
        for (String suffix : List.of("/capabilities", "/" + UUID.randomUUID())) {
            mvc.perform(get("/api/v1/image-prompt-sources" + suffix).with(anonymous())).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(sources);
    }

    @Test void uploadTextAndConsentRequireCsrfBeforeLocalPreparation() throws Exception {
        mvc.perform(multipart("/api/v1/image-prompt-sources").file(new MockMultipartFile("file", "safe.txt", "text/plain", new byte[]{65}))
                .with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        for (String suffix : List.of("/text", "/consents")) {
            mvc.perform(post("/api/v1/image-prompt-sources" + suffix).with(csrf().useInvalidToken())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
        }
        verifyNoInteractions(sources);
    }

    @Test void invalidCollectionIsRejectedBeforeServiceOrModel() throws Exception {
        mvc.perform(post("/api/v1/image-prompt-sources/text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"资料\",\"entries\":[]}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/image-prompt-sources/consents").contentType(MediaType.APPLICATION_JSON)
                .content("{}")) .andExpect(status().isBadRequest());
        verifyNoInteractions(sources);
    }

    @Test void validPreparationUsesOnlyServerActor() throws Exception {
        when(sources.capabilities()).thenReturn(new Capabilities(20, 52428800, 40, 120000, 5000, List.of("txt")));
        mvc.perform(get("/api/v1/image-prompt-sources/capabilities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxSelectedUnits").value(40));
        mvc.perform(post("/api/v1/image-prompt-sources/text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"资料\",\"entries\":[{\"path\":\"readme.txt\",\"content\":\"安全正文\"}]}"))
                .andExpect(status().isOk());
        verify(sources).text(eq(actor), argThat(upload -> upload.entries().getFirst().path().equals("readme.txt")));
    }

    @Test void excessiveJsonBodyReturnsStable413BeforePreparingObjects() throws Exception {
        byte[] body = new byte[ImagePromptSourceBodyAdvice.MAX_JSON_BYTES + 1];
        java.util.Arrays.fill(body, (byte) 32);
        mvc.perform(post("/api/v1/image-prompt-sources/text").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("SOURCE_BODY_TOO_LARGE"));
        verifyNoInteractions(sources);
    }
}
