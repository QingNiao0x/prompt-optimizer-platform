package com.promptoptimizer.analytics.dto;

import com.promptoptimizer.analytics.controller.AdminAnalyticsController;
import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        for (String endpoint : java.util.List.of("dashboard", "usage-ranking", "operations")) {
            mockMvc.perform(get("/api/v1/admin/analytics/" + endpoint).param("email", "demo@example.test").with(anonymous()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void ordinaryUserIsForbidden() throws Exception {
        for (String endpoint : java.util.List.of("dashboard", "usage-ranking", "operations")) {
            mockMvc.perform(get("/api/v1/admin/analytics/" + endpoint).param("displayName", "演示")
                            .with(user("ordinary").roles("USER")))
                    .andExpect(status().isForbidden());
        }
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

    @Test
    void accountKeywordsBindToAllThreeQueryRecords() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/dashboard").param("email", "demo@example.test").param("displayName", "名称")
                        .with(user("platform-admin").roles("PLATFORM_ADMIN")))
                .andExpect(result -> assertThat(result.getResolvedException()).isNull())
                .andExpect(status().isOk());
        verify(analyticsService).dashboard(new DashboardQuery("TODAY", null, null, null, "demo@example.test", "名称"));
        mockMvc.perform(get("/api/v1/admin/analytics/usage-ranking").param("period", "DAY").param("date", "2026-09-25")
                        .param("email", "demo@example.test").param("displayName", "名称")
                        .with(user("platform-admin").roles("PLATFORM_ADMIN"))).andExpect(status().isOk());
        verify(analyticsService).usageRanking(new UsageRankingQuery("DAY", "2026-09-25", null, null, "demo@example.test", "名称"));
        mockMvc.perform(get("/api/v1/admin/analytics/operations").param("fromDate", "2026-09-25").param("toDate", "2026-09-25")
                        .param("email", "demo@example.test").param("displayName", "名称")
                        .with(user("platform-admin").roles("PLATFORM_ADMIN"))).andExpect(status().isOk());
        verify(analyticsService).operationLogs(new OperationLogQuery("2026-09-25", "2026-09-25", null, null, null, null, "demo@example.test", "名称"));
    }

    @Test
    void oversizedKeywordsReturnBadRequestBeforeCallingTheService() throws Exception {
        for (String endpoint : java.util.List.of("dashboard", "usage-ranking", "operations")) {
            for (var entry : java.util.Map.of("email", "x".repeat(321), "displayName", "名".repeat(81)).entrySet()) {
                mockMvc.perform(get("/api/v1/admin/analytics/" + endpoint)
                                .param("period", "DAY").param("date", "2026-09-25")
                                .param("fromDate", "2026-09-25").param("toDate", "2026-09-25")
                                .param(entry.getKey(), entry.getValue())
                                .with(user("platform-admin").roles("PLATFORM_ADMIN"))).andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(analyticsService);
    }
}
