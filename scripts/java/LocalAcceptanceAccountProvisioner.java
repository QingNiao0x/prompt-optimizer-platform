import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 为已授权的本地业务验收创建独立普通账户、租户和工作区，随后仍按正常验证码登录。
 * 不启动 Web、不执行迁移、不提升角色或修改已有账户；凭据只从进程环境读取且不写入证据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class LocalAcceptanceAccountProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAcceptanceAccountProvisioner.class);

    /** 只允许向本仓库 tmp 下的新文件写入合成账户标识，数据库必须为回环地址。 */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of("").toAbsolutePath().normalize();
        if (args.length != 1) throw new IllegalArgumentException("Expected local evidence path");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        if (!output.startsWith(repo.resolve("tmp")) || Files.exists(output)) {
            throw new IllegalArgumentException("Evidence path must be new and inside workspace tmp");
        }
        String username = System.getenv("PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER");
        String password = System.getenv("PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD");
        if (username == null || !username.matches("qa_[a-z0-9]{12,32}") || password == null
                || !com.promptoptimizer.identity.service.PasswordPolicy.meets(password)) {
            throw new IllegalArgumentException("Synthetic account environment unavailable");
        }
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("local-acceptance",
                new FileSystemResource(repo.resolve("services/api/src/main/resources/application.yml")))) {
            environment.getPropertySources().addLast(source);
        }
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):\\d+/[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Only a loopback PostgreSQL database is allowed");
        }
        var app = new SpringApplication(LocalConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(environment);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN", "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")) {
            var mapper = context.getBean(IdentityProvisioningMapper.class);
            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            UUID workspaceId = UUID.randomUUID();
            var identity = UserIdentityKey.username(username);
            String hash = new BCryptPasswordEncoder(12).encode(password);
            transactions.executeWithoutResult(status -> {
                if (mapper.existsIdentity("USERNAME", "local", identity.normalizedIdentifier())) {
                    throw new IllegalStateException("Synthetic identity already exists");
                }
                mapper.insertTenant(tenantId, "本地业务验收租户");
                mapper.insertUserAccount(userId, tenantId, null, "本地业务验收", hash, "USER");
                mapper.insertWorkspace(workspaceId, tenantId, "本地业务验收", "仅使用合成资料的本地验收", userId);
                mapper.insertWorkspaceMember(workspaceId, userId, "OWNER");
                mapper.insertUserIdentity(UUID.randomUUID(), userId, "USERNAME", "local", username,
                        identity.normalizedIdentifier(), "ACTIVE", OffsetDateTime.now());
            });
            Files.createDirectories(output.getParent());
            Files.writeString(output, new ObjectMapper().writeValueAsString(Map.of(
                    "kind", "LOCAL_SYNTHETIC_FIXTURE", "identifier", username,
                    "userId", userId, "tenantId", tenantId, "workspaceId", workspaceId,
                    "schemaChanged", false, "existingAccountsChanged", false)), StandardOpenOption.CREATE_NEW);
            LOGGER.info("event=acceptance.account.created role=USER schemaChanged=false existingAccountsChanged=false");
        }
    }

    /** 只装配持久化自动配置和现有参数化 Mapper，不运行生产身份引导或外部邮件投递。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.promptoptimizer.identity.mapper")
    static class LocalConfiguration { }
}
