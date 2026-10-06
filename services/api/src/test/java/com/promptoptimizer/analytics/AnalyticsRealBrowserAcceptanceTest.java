package com.promptoptimizer.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.infrastructure.AuditEventDatabaseWriter;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.support.AnalyticsAcceptanceMapper;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.security.BootstrapAdminAccountInitializer;
import com.promptoptimizer.identity.security.BootstrapUserPasswordInitializer;
import com.promptoptimizer.identity.service.LoginCaptchaService;
import com.promptoptimizer.identity.service.impl.LoginCaptchaServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 显式启用的真实 Chrome → Vite /api 代理 → HTTP/Security/Redis → MyBatis/PostgreSQL 验收。
 * 仅数据库 commit/rollback 被测试包装以便随机账号和业务数据最终整体回滚；不是数据库持久提交或掉电验收。
 * 验证码使用生产生成/验证流程，测试仅读取答案，经父子进程管道传递，绝不输出凭据或会话内容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "analytics.browser.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.flyway.enabled=false", "app.provider.mode=mock",
        "app.analytics.recharge.enabled=false", "app.analytics.trusted-proxies=",
        "app.analytics.geoip.database=",
        "app.analytics.delivery.retry-initial-ms=100", "app.analytics.delivery.retry-max-ms=1000",
        "decorator.datasource.enabled=false", "logging.level.com.promptoptimizer=WARN",
        "mybatis-plus.configuration.local-cache-scope=STATEMENT"
})
@Import(AnalyticsRealBrowserAcceptanceTest.RollbackConfiguration.class)
class AnalyticsRealBrowserAcceptanceTest {
    private static final String RUN = UUID.randomUUID().toString();
    private static final Path JOURNAL = createJournal();
    @LocalServerPort private int apiPort;
    @Autowired private ObjectMapper json;
    @Autowired private IdentityProvisioningMapper provisioning;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AnalyticsAcceptanceMapper inspection;
    @Autowired private RollbackDataSource dataSource;
    @Autowired private AuditEventDelivery delivery;
    @Autowired private io.micrometer.core.instrument.MeterRegistry metrics;
    @SpyBean private LoginCaptchaServiceImpl captchaService;
    @SpyBean private AuditEventDatabaseWriter writer;
    @MockBean private BootstrapAdminAccountInitializer adminInitializer;
    @MockBean private BootstrapUserPasswordInitializer userInitializer;

    @DynamicPropertySource
    static void isolateShortLivedAndDurableState(DynamicPropertyRegistry properties) {
        properties.add("spring.session.redis.namespace", () -> "analytics-browser-acceptance:" + RUN);
        properties.add("app.analytics.delivery.journal-directory", JOURNAL::toString);
    }

    private static Path createJournal() {
        try { return Files.createTempDirectory("analytics-browser-journal-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException("无法创建验收专用审计目录", failure); }
    }

    @Test
    void browserHttpAuthenticationHomeVisitsDatabaseRecoveryAndAdminChartsWorkTogether() throws Exception {
        AtomicBoolean outage = new AtomicBoolean();
        AtomicReference<String> latestCaptcha = new AtomicReference<>();
        doAnswer(call -> {
            byte[] image = (byte[]) call.callRealMethod();
            HttpServletRequest request = call.getArgument(0);
            latestCaptcha.set((String) request.getSession(false).getAttribute(LoginCaptchaService.ATTRIBUTE));
            return image;
        }).when(captchaService).issue(any(HttpServletRequest.class));
        // 故障在真实 SQL 执行前注入，不使 PostgreSQL 外层事务进入 aborted 状态；正常重放仍执行生产 Mapper。
        doAnswer(call -> {
            if (outage.get()) throw new DataAccessResourceFailureException("Synthetic acceptance outage");
            return call.callRealMethod();
        }).when(writer).write(any(PendingAuditEvent.class));
        String password = UUID.randomUUID() + "Aa!";
        String hash = encoder.encode(password);
        Account member = createAccount("USER", hash);
        Account admin = createAccount("PLATFORM_ADMIN", hash);
        Process browser = null;
        try {
            Path repository = Path.of("..", "..").toAbsolutePath().normalize();
            var launch = new ProcessBuilder("node", repository.resolve("scripts/analytics-real-browser-acceptance.mjs").toString());
            launch.directory(repository.toFile());
            launch.redirectError(ProcessBuilder.Redirect.to(Path.of("target", "analytics-browser-errors.log").toFile()));
            browser = launch.start();
            try (var output = new BufferedWriter(new OutputStreamWriter(browser.getOutputStream(), StandardCharsets.UTF_8));
                 var input = new BufferedReader(new InputStreamReader(browser.getInputStream(), StandardCharsets.UTF_8))) {
                write(output, Map.of("apiPort", apiPort, "webPort", 5192, "password", password,
                        "memberEmail", member.email(), "adminEmail", admin.email(),
                        "memberId", member.userId(), "report", repository.resolve("services/api/target/analytics-real-browser-acceptance.json").toString()));
                String command;
                boolean completed = false;
                while ((command = input.readLine()) != null) {
                    switch (command) {
                        case "CAPTCHA" -> write(output, Map.of("captcha", latestCaptcha.get()));
                        case "OUTAGE_ON" -> { outage.set(true); write(output, Map.of("ok", true)); }
                        case "OUTAGE_OFF" -> { outage.set(false); write(output, Map.of("ok", true)); }
                        case "INSPECTION" -> {
                            List<String> events = inspection.eventTypes(member.userId());
                            write(output, Map.of("visits", events.stream().filter("APP_VISIT"::equals).count(),
                                    "events", events.size(), "pending", delivery.status().pendingEvents(),
                                    "directEnhancementCount", events.stream().filter("DIRECT_OPTIMIZATION_SUBMITTED"::equals).count(),
                                    "planCompletedCount", events.stream().filter("PLAN_COMPLETED"::equals).count(),
                                    "databaseFailures", delivery.status().databaseFailures()));
                        }
                        case "DONE" -> completed = true;
                        default -> throw new IllegalStateException("浏览器验收返回未定义状态；详情仅包含安全阶段代码");
                    }
                }
                assertThat(completed).as("浏览器必须完整执行验收步骤").isTrue();
            }
            assertThat(browser.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(browser.exitValue()).as("真实浏览器验收进程退出状态").isZero();
            assertThat(delivery.status().pendingEvents()).isZero();
            assertThat(metrics.get("optimization.feature.uses").tag("event", "DIRECT_OPTIMIZATION_SUBMITTED").counter().count()).isEqualTo(3);
            assertThat(metrics.get("optimization.feature.uses").tag("event", "PLAN_COMPLETED").counter().count()).isEqualTo(2);
        } finally {
            outage.set(false);
            if (browser != null && browser.isAlive()) browser.destroyForcibly();
            delivery.close();
            dataSource.rollbackFixture();
            for (Account account : List.of(member, admin)) {
                assertThat(inspection.remainingRows(account.userId(), account.tenantId(), account.workspaceId()))
                        .as("浏览器产生的账号、会话、优化记录和审计明细全部回滚").isZero();
            }
        }
    }

    /** 所有关联数据归属随机测试租户，生产认证读取同一真实连接中的这些未提交记录。 */
    private Account createAccount(String role, String hash) {
        Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() + "@analytics-browser.invalid");
        provisioning.insertTenant(account.tenantId(), "analytics-acceptance");
        provisioning.insertUserAccount(account.userId(), account.tenantId(), account.email(), "analytics-acceptance", hash, role);
        provisioning.insertWorkspace(account.workspaceId(), account.tenantId(), "analytics-acceptance", "", account.userId());
        provisioning.insertWorkspaceMember(account.workspaceId(), account.userId(), "OWNER");
        provisioning.insertUserIdentity(UUID.randomUUID(), account.userId(), "EMAIL", "local", account.email(), account.email(), "ACTIVE", OffsetDateTime.now());
        return account;
    }

    private void write(BufferedWriter output, Object value) throws java.io.IOException {
        output.write(json.writeValueAsString(value));
        output.newLine();
        output.flush();
    }

    private record Account(UUID userId, UUID tenantId, UUID workspaceId, String email) { }

    /** 替换的只是测试数据源事务边界；数据源属性沿用本地安全配置，不读取或输出部署凭据。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RollbackConfiguration {
        @Bean
        RollbackDataSource dataSource(DataSourceProperties properties) throws SQLException {
            return new RollbackDataSource(properties.initializeDataSourceBuilder().build());
        }
    }

    /**
     * HTTP 和后台 writer 共享一个真实 PostgreSQL 外层事务，最终仅由验收代码物理回滚。
     * 这是防止测试账号污染本地库的适配器；真实提交、ack 与重启语义须由独立故障演练验收。
     */
    static final class RollbackDataSource extends AbstractDataSource implements AutoCloseable {
        private final DataSource owner;
        private final Connection physical;
        private final Connection shared;

        RollbackDataSource(DataSource owner) throws SQLException {
            this.owner = owner;
            physical = owner.getConnection();
            physical.setAutoCommit(false);
            shared = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                if (List.of("commit", "rollback", "close", "setAutoCommit", "setReadOnly", "setTransactionIsolation").contains(method.getName())) return null;
                if (method.getName().equals("getAutoCommit")) return false;
                synchronized (physical) {
                    try { return method.invoke(physical, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }
            });
        }

        @Override public Connection getConnection() { return shared; }
        @Override public Connection getConnection(String username, String password) { return shared; }
        void rollbackFixture() throws SQLException { physical.rollback(); }
        @Override public void close() throws Exception {
            physical.rollback();
            physical.close();
            if (owner instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
