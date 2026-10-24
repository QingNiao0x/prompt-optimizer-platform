package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import com.promptoptimizer.imageprompt.service.*;
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

/** 使用真实安全链验证图像入口、CSRF、管理员能力配置和输入边界，不调用模型。 */
@WebMvcTest({ImagePromptController.class, ImagePromptAdminController.class})
@Import({RequestIdFilter.class, AuthenticatedMvcTestConfiguration.class})
class ImagePromptControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean ImagePromptService service;
    @MockBean ImagePromptAssetService assets;
    @MockBean ImagePromptHistoryService history;
    @MockBean ImagePromptModelService models;
    @MockBean CurrentActor current;
    @MockBean PlatformAdminAccess access;

    @Test void privatePreviewsHistoryOperationsAndCapabilitiesRequireAuthentication() throws Exception {
        for (String path : java.util.List.of("/image-prompt-assets/"+UUID.randomUUID(), "/image-prompts/history", "/image-prompts/capabilities", "/image-prompts/operations/"+UUID.randomUUID())) {
            mvc.perform(get("/api/v1"+path).with(anonymous())).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service, assets, history);
    }
    @Test void mutationRequiresValidCsrfBeforeModelOrStorageWork() throws Exception {
        mvc.perform(post("/api/v1/image-prompts").with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, assets, history);
    }
    @Test void invalidInputNeverReachesPaidOperation() throws Exception {
        mvc.perform(post("/api/v1/image-prompts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void ordinaryUsersCannotEnableVisionEvenWithCsrf() throws Exception {
        mvc.perform(put("/api/v1/admin/image-prompt-models").param("modelId", "fixture/model").with(user("ordinary").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"maxImages\":1,\"evidence\":\"fixture\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(models);
    }
    @Test void staleAdminRoleStillRequiresCurrentPermissionCheck() throws Exception {
        doThrow(new AccessDeniedException("revoked fixture")).when(access).require();
        mvc.perform(get("/api/v1/admin/image-prompt-models").with(user("admin").roles("PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(models);
    }
}
