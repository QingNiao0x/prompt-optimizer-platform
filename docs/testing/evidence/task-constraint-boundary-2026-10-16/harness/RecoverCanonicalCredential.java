import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import org.springframework.boot.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import java.util.*;

/** 只修复固定本机合成账号的测试凭据；不创建或合并任何账户、工作区和历史。 */
public class RecoverCanonicalCredential {
    public static void main(String[] args) throws Exception {
        Path reference = Path.of("tmp/local-acceptance/account.json");
        Path runtime = Path.of("tmp/me00a-candidate16-20261016");
        var json = new ObjectMapper();
        var account = json.readTree(reference.toFile());
        String password = System.getenv("PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD");
        if (!"LOCAL_SYNTHETIC_FIXTURE".equals(account.path("kind").asText())
                || !account.path("identifier").asText().startsWith("qa_")
                || password == null || password.length() < 20) throw new IllegalStateException("Synthetic fixture only");
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("fixture", new FileSystemResource("services/api/src/main/resources/application.yml")))
            env.getPropertySources().addLast(source);
        if (!env.getProperty("spring.datasource.url", "").matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):\\d+/[a-zA-Z0-9_-]+"))
            throw new IllegalStateException("Local database only");
        var app = new SpringApplication(LocalAcceptanceAccountProvisioner.LocalConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE); app.setEnvironment(env);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            var mapper = context.getBean(UserAccountMapper.class);
            UUID id = UUID.fromString(account.path("userId").asText());
            var entity = mapper.selectById(id);
            if (entity == null || !entity.getTenantId().toString().equals(account.path("tenantId").asText()))
                throw new IllegalStateException("Canonical fixture identity mismatch");
            if (!mapper.selectDefaultWorkspaceId(id, entity.getTenantId()).toString().equals(account.path("workspaceId").asText())) throw new IllegalStateException("Canonical workspace mismatch");
            if (mapper.updatePasswordHash(id, new BCryptPasswordEncoder(12).encode(password)) != 1)
                throw new IllegalStateException("Canonical credential repair failed");
            Files.writeString(runtime.resolve("credential.json"), json.writeValueAsString(Map.of("password", password)), StandardOpenOption.CREATE_NEW);
            Files.writeString(runtime.resolve("credential-recovery.json"), json.writeValueAsString(Map.of(
                    "sameAccount", true, "newAccountsCreated", 0, "newTenantsCreated", 0,
                    "newWorkspacesCreated", 0, "historyUnchangedByRecovery", true, "normalCaptchaLoginRequired", true)), StandardOpenOption.CREATE_NEW);
        }
    }
}
