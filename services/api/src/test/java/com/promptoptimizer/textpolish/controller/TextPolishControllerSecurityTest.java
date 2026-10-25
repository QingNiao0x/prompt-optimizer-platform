package com.promptoptimizer.textpolish.controller;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import com.promptoptimizer.textpolish.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实安全链验证文章私有入口、CSRF和管理员模型配置，模型及存储为受控桩。 */
@WebMvcTest({TextPolishController.class, TextPolishAdminController.class})
@Import({RequestIdFilter.class, AuthenticatedMvcTestConfiguration.class})
class TextPolishControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean TextPolishService service;
    @MockBean TextPolishHistoryService history;
    @MockBean TextPolishModelService models;
    @MockBean CurrentActor current;
    @MockBean PlatformAdminAccess access;
    @Test void privateCapabilitiesHistoryAndRecoveryRequireAuthentication() throws Exception {
        for (String path : java.util.List.of("/capabilities", "/history", "/operations/" + UUID.randomUUID()))
            mvc.perform(get("/api/v1/text-polish"+path).with(anonymous())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service,history);
    }
    @Test void invalidCsrfAndMissingInputCannotCallModels() throws Exception {
        mvc.perform(post("/api/v1/text-polish").with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/text-polish").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/text-polish").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+UUID.randomUUID()+"\",\"input\":{\"sourceText\":\"文章\",\"style\":\"INVALID\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/text-polish").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+UUID.randomUUID()+"\",\"input\":{\"sourceText\":\"文章\",\"style\":1}}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void ordinaryAndRevokedAdminsCannotChangeModelPolicy() throws Exception {
        mvc.perform(put("/api/v1/admin/models/text-polish").with(user("ordinary").roles("USER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabledModelIds\":[],\"defaultModelId\":null}")).andExpect(status().isForbidden());
        doThrow(new AccessDeniedException("revoked fixture")).when(access).require();
        mvc.perform(get("/api/v1/admin/models/text-polish").with(user("admin").roles("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(models);
    }
}
