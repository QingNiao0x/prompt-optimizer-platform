import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 复用唯一的本地合成验收账户，拒绝旧脚本按轮次创建用户、租户和工作区。
 * 仅写运行目录中的账户引用，不启动 Web、不修改数据库或凭据；仍按正常验证码登录。
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
        Path canonical = repo.resolve("tmp/local-acceptance/account.json");
        if (!Files.isRegularFile(canonical)) throw new IllegalStateException("Canonical local fixture missing; never create a replacement account");
        var account = new ObjectMapper().readTree(canonical.toFile());
        String identifier = System.getenv("PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER");
        if (!"LOCAL_SYNTHETIC_FIXTURE".equals(account.path("kind").asText())
                || identifier != null && !identifier.equals(account.path("identifier").asText()))
            throw new IllegalStateException("Use the canonical fixture identifier; per-run identities are no longer allowed");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(account), StandardOpenOption.CREATE_NEW);
        LOGGER.info("event=acceptance.account.reused newAccountsCreated=0");
    }

    /** 只装配持久化自动配置和现有参数化 Mapper，不运行生产身份引导或外部邮件投递。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.promptoptimizer.identity.mapper")
    static class LocalConfiguration { }
}
