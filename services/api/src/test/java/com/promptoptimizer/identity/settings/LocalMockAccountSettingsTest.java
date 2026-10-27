package com.promptoptimizer.identity.settings;

import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.security.SecurityConfiguration;
import com.promptoptimizer.identity.security.SecurityContextCurrentActor;
import com.promptoptimizer.identity.security.SecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 本地Mock模式使用真实安全链但没有数据库设置服务：只读投影可用，写操作明确拒绝。
 * 使用Servlet请求验收，避免Windows测试环境的嵌入式服务器回环限制干扰业务判断。
 * @author QingNiao
 * @since 0.1.0
 */
@WebMvcTest(AccountSettingsController.class)
@ActiveProfiles("local-mock")
@Import({SecurityConfiguration.class, SecurityContextCurrentActor.class, SecurityErrorWriter.class,
        GlobalExceptionHandler.class, LocalMockAccountSettingsTest.FixtureConfiguration.class})
class LocalMockAccountSettingsTest {
    @Autowired MockMvc mvc;

    @Test
    void authenticatedMockCanReadButCannotWriteRealAccount() throws Exception {
        var principal = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "mock-settings@example.test", "合成Mock资料", "", "ACTIVE", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(get("/api/v1/me/settings").with(user(principal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("合成Mock资料"))
                .andExpect(jsonPath("$.data.emailBindingAvailable").value(false))
                .andExpect(jsonPath("$.data.accountDeletionAvailable").value(false));
        mvc.perform(patch("/api/v1/me/settings/profile").with(user(principal)).with(csrf())
                        .contentType("application/json").content("{\"displayName\":\"不得写业务库\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SETTINGS_UNAVAILABLE"));
    }

    @Test
    void mockModeKeepsAuthenticationAndCsrfRequirements() throws Exception {
        mvc.perform(get("/api/v1/me/settings")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/me/settings/profile").contentType("application/json")
                .content("{\"displayName\":\"不绕过CSRF\"}")).andExpect(status().isForbidden());
    }

    /** Mock只提供安全链装配所需的查找器，不允许创建或验证真实账户。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean UserDetailsService users() { return name -> { throw new UsernameNotFoundException("synthetic fixture only"); }; }
    }
}
