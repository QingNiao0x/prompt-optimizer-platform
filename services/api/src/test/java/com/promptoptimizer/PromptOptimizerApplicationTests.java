package com.promptoptimizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "spring.flyway.enabled=false",
                "app.history.enabled=false"
        }
)
class PromptOptimizerApplicationTests {

    @MockBean
    private com.promptoptimizer.identity.infrastructure.persistence.UserAccountRepository userAccountRepository;

    @MockBean
    private com.promptoptimizer.identity.infrastructure.persistence.UserIdentityRepository userIdentityRepository;

    @Test
    void contextLoads() {
    }
}
