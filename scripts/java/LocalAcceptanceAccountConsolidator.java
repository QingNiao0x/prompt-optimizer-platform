package com.promptoptimizer.localacceptance;

import com.promptoptimizer.identity.maintenance.LocalAcceptanceRows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 合并已授权本机合成账户的历史，保留来源与删除状态；默认只检查，永不创建账户。
 * @author QingNiao
 * @since 0.1.0
 */
public class LocalAcceptanceAccountConsolidator {
    /** apply 必须在完成只读检查后显式调用；备份写入成功且全部作用范围匹配才提交。 */
    public static void main(String[] args) throws Exception {
        boolean apply = args.length == 1 && args[0].equals("apply");
        boolean verify = args.length == 1 && args[0].equals("verify");
        Path repo = Path.of("").toAbsolutePath().normalize();
        Path root = repo.resolve("tmp/local-acceptance");
        var json = new ObjectMapper();
        var fixture = json.readTree(repo.resolve("tmp/enhancement-quality-20261012/account.json").toFile());
        if (!fixture.path("kind").asText().equals("LOCAL_SYNTHETIC_FIXTURE")) throw new IllegalStateException("Not synthetic fixture");
        UUID user = UUID.fromString(fixture.path("userId").asText());
        UUID tenant = UUID.fromString(fixture.path("tenantId").asText());
        UUID workspace = UUID.fromString(fixture.path("workspaceId").asText());
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("local", new FileSystemResource(
                repo.resolve("services/api/src/main/resources/application.yml")))) environment.getPropertySources().addLast(source);
        if (!environment.getProperty("spring.datasource.url", "").matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):\\d+/[a-zA-Z0-9_-]+"))
            throw new IllegalStateException("Loopback database required");
        var app = new SpringApplication(Configuration.class);
        app.setEnvironment(environment); app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")) {
            var mapper = context.getBean(LocalAcceptanceMaintenanceMapper.class);
            if (verify) {
                var oldRows = json.readTree(root.resolve("history-backup.json").toFile());
                var current = mapper.snapshot(List.of(user)).stream().filter(row -> row.kind().equals("optimization_record"))
                        .map(row -> { try { return json.readTree(row.data()); } catch (Exception failure) { throw new IllegalStateException(failure); } })
                        .collect(java.util.stream.Collectors.toMap(row -> row.path("id").asText(), row -> row));
                int checked = 0;
                for (var oldRow : oldRows) if (oldRow.path("kind").asText().equals("optimization_record")) {
                    var before = json.readTree(oldRow.path("data").asText());
                    var after = current.get(before.path("id").asText());
                    if (after == null) throw new IllegalStateException("History ID lost");
                    for (String field : List.of("raw_prompt", "optimized_prompt", "context_snapshot", "permission_policy", "created_at",
                            "deleted_at", "session_id", "provider_config_id", "retention_until", "latency_ms"))
                        if (!before.path(field).equals(after.path(field))) throw new IllegalStateException("History content changed: " + field);
                    var metadata = after.path("result_metadata").deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).remove("localAcceptanceConsolidation");
                    if (!metadata.equals(before.path("result_metadata"))) throw new IllegalStateException("Original metadata changed");
                    checked++;
                }
                var result = Map.of("movedRecordsVerified", checked, "canonicalVisibleHistory", mapper.visibleHistory(user, tenant, workspace),
                        "remainingSyntheticAccounts", mapper.candidates().size(), "contentAndIdsPreserved", true, "newAccountsCreated", 0);
                Files.writeString(root.resolve("consolidation-verification.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardOpenOption.CREATE_NEW);
                System.out.println(json.writeValueAsString(result));
                return;
            }
            var rows = mapper.candidates().stream().map(LocalAcceptanceRows.Candidate::asMap).toList();
            if (rows.stream().noneMatch(row -> user.toString().equals(row.get("id")) && tenant.toString().equals(row.get("tenant_id"))))
                throw new IllegalStateException("Canonical account not in verified synthetic scope");
            if (rows.stream().anyMatch(row -> count(row, "other_members") != 0 || count(row, "other_users") != 0))
                throw new IllegalStateException("Shared tenant or foreign workspace member; consolidation refused");
            var users = rows.stream().map(row -> UUID.fromString((String) row.get("id"))).filter(id -> !id.equals(user)).toList();
            long records = rows.stream().mapToLong(row -> count(row, "records")).sum();
            long visible = rows.stream().mapToLong(row -> count(row, "visible")).sum();
            Files.createDirectories(root);
            var summary = Map.of("syntheticAccounts", rows.size(), "sourceAccounts", users.size(), "totalHistory", records,
                    "visibleHistory", visible, "canonicalUserId", user.toString(), "newAccountsCreated", 0, "applied", apply);
            if (!apply) Files.writeString(root.resolve("consolidation-inspection.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardOpenOption.CREATE_NEW);
            if (apply && !users.isEmpty()) {
                var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
                transactions.executeWithoutResult(status -> {
                    if (mapper.lockUsers(users).size() != users.size()) throw new IllegalStateException("Source account changed");
                    // 写备份在事务变更前完成；保留全部正文和原始范围，仅限本机私有目录，不进入证据提交。
                    try { Files.writeString(root.resolve("history-backup.json"), json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(mapper.snapshot(users)), StandardOpenOption.CREATE_NEW); }
                    catch (Exception failure) { throw new IllegalStateException("Backup failed", failure); }
                    int moved = mapper.mergeRecords(users, user, tenant, workspace);
                    if (moved != records - rows.stream().filter(row -> user.toString().equals(row.get("id")))
                            .mapToLong(row -> count(row, "records")).sum()) throw new IllegalStateException("History count changed");
                    mapper.mergeSessions(users, user, tenant, workspace);
                    // 合并只停用重复账户；身份、原工作区与租户状态继续保留，不额外扩大维护范围。
                    mapper.disableUsers(users);
                    if (mapper.visibleHistory(user, tenant, workspace) != visible) throw new IllegalStateException("Visible history parity failed");
                });
            }
            if (apply) {
                Files.writeString(root.resolve("account.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(fixture), StandardOpenOption.CREATE_NEW);
                Files.writeString(root.resolve("consolidation-result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardOpenOption.CREATE_NEW);
            }
            // 控制台仅报数量；登录键、密码、正文及备份不进入工具输出。
            System.out.println(json.writeValueAsString(summary));
        }
    }

    private static long count(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    /** 本地维护映射独立扫描；普通业务身份、邮件和模型服务均不启动。 */
    @SpringBootConfiguration @EnableAutoConfiguration @MapperScan("com.promptoptimizer.localacceptance")
    static class Configuration { }
}
