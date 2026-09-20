package com.promptoptimizer.identity.support;

import com.promptoptimizer.identity.infrastructure.security.SecurityConfiguration;
import com.promptoptimizer.identity.infrastructure.security.SecurityErrorWriter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 控制器契约测试使用真实安全链和显式认证/CSRF；安全拒绝分支另由集成测试覆盖。 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfiguration.class, SecurityErrorWriter.class})
public class AuthenticatedMvcTestConfiguration {
    @Bean
    UserDetailsService testUserDetailsService() {
        return username -> { throw new UsernameNotFoundException("Not a login fixture"); };
    }

    @Bean
    MockMvcBuilderCustomizer authenticatedRequests() {
        return builder -> builder.defaultRequest(get("/").with(user("contract-test")).with(csrf()));
    }
}
