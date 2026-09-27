package com.promptoptimizer.analytics.dto;

import com.promptoptimizer.analytics.controller.AdminAnalyticsController;
import com.promptoptimizer.analytics.service.impl.AdminAnalyticsService;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证统计和审计接口在未登录、普通账号和平台管理员三种身份下的服务端访问控制。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@WebMvcTest(AdminAnalyticsController.class)
@Import({RequestIdFilter.class, AuthenticatedMvcTestConfiguration.class})
class AdminAnalyticsControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAnalyticsService analyticsService;

    @MockBean
    private PlatformAdminAccess platformAdminAccess;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/dashboard").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryUserIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/dashboard")
                        .with(user("ordinary").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdministratorPassesBothSecurityLayers() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/usage-ranking")
                        .param("period", "DAY")
                        .param("date", "2026-09-25")
                        .with(user("platform-admin").roles("PLATFORM_ADMIN")))
                .andExpect(status().isOk());

        verify(platformAdminAccess).require();
    }
}
