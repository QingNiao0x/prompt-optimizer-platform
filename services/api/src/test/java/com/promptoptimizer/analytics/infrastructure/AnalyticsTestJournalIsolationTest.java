package com.promptoptimizer.analytics.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验测试资源默认隔离审计目录，防止虚拟账号事件污染开发实例的持久 journal。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AnalyticsTestJournalIsolationTest {
    @Test
    void separateTestContextsResolveDifferentTemporaryJournals() throws Exception {
        String first = journalDirectory();
        String second = journalDirectory();
        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(Path.of(first).toAbsolutePath().normalize().startsWith(
                Path.of(System.getProperty("user.dir"), "target", "test-analytics-journal").toAbsolutePath().normalize()))
                .isTrue();
        assertThat(first).doesNotContain("${");
    }

    /** 按 Boot 的随机值解析方式加载测试属性，不启动应用或读取部署配置。 */
    private String journalDirectory() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        RandomValuePropertySource.addToEnvironment(environment);
        environment.getPropertySources().addFirst(new ResourcePropertySource("classpath:application.properties"));
        return environment.getProperty("app.analytics.delivery.journal-directory");
    }
}
