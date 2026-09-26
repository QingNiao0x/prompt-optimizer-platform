package com.promptoptimizer.history.mapper;

import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 SQL 确认历史查询不会读到其他租户、其他工作区或已逻辑删除的记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "spring.session.store-type=none",
        "app.security.login-guard.require-redis=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@Transactional
class OptimizationRecordScopeDatabaseTest {

    @Autowired
    private OptimizationRecordMapper recordMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    void listAndDeleteStayInsideTenantWorkspaceAndIgnoreDeletedRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID workspaceA = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID visibleId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        UUID otherWorkspaceId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        insertTenantUser(jdbc, tenantA, userA, "a@scope.test");
        insertWorkspaceSession(jdbc, tenantA, userA, workspaceA, sessionA);
        insertWorkspaceSession(jdbc, tenantA, userA, otherWorkspace, otherSession);
        insertTenantUser(jdbc, tenantB, userB, "b@scope.test");
        insertWorkspaceSession(jdbc, tenantB, userB, workspaceB, sessionB);
        insertRecord(jdbc, visibleId, tenantA, workspaceA, sessionA, userA, "当前工作区可见记录", null);
        insertRecord(jdbc, deletedId, tenantA, workspaceA, sessionA, userA, "当前工作区已删除记录",
                OffsetDateTime.now(ZoneOffset.UTC));
        insertRecord(jdbc, otherWorkspaceId, tenantA, otherWorkspace, otherSession, userA, "同租户其他工作区", null);
        insertRecord(jdbc, otherTenantId, tenantB, workspaceB, sessionB, userB, "其他租户记录", null);

        List<OptimizationRecordEntity> records = recordMapper.selectPageByScope(
                tenantA, workspaceA, null, null, null, 20, 0L);
        assertThat(records).extracting(OptimizationRecordEntity::getId).containsExactly(visibleId);
        assertThat(records.getFirst().getRawPrompt()).contains("当前工作区可见记录");
        assertThat(records.getFirst().getRawPrompt()).doesNotContain("其他租户", "已删除", "其他工作区");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM optimization_record
                 WHERE tenant_id = ? AND workspace_id = ? AND deleted_at IS NULL
                """,
                Integer.class,
                tenantA,
                workspaceA
        )).isEqualTo(1);
        assertThat(recordMapper.selectByIdAndScope(visibleId, tenantA, workspaceA)).isNotNull();
        assertThat(recordMapper.selectByIdAndScope(deletedId, tenantA, workspaceA)).isNull();
        assertThat(recordMapper.selectByIdAndScope(otherWorkspaceId, tenantA, workspaceA)).isNull();
        assertThat(recordMapper.selectByIdAndScope(otherTenantId, tenantA, workspaceA)).isNull();

        OffsetDateTime attemptedDelete = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(recordMapper.markDeletedByIdAndScope(otherTenantId, tenantA, workspaceA, attemptedDelete)).isZero();
        assertThat(recordMapper.markDeletedByIdAndScope(otherWorkspaceId, tenantA, workspaceA, attemptedDelete)).isZero();
        assertThat(recordMapper.markDeletedByIdAndScope(deletedId, tenantA, workspaceA, attemptedDelete)).isZero();
        assertThat(recordMapper.markDeletedByIdAndScope(visibleId, tenantA, workspaceA, attemptedDelete)).isEqualTo(1);
        assertThat(recordMapper.selectByIdAndScope(visibleId, tenantA, workspaceA)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM optimization_record WHERE id = ?",
                Boolean.class,
                otherTenantId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM optimization_record WHERE id = ?",
                Boolean.class,
                otherWorkspaceId
        )).isTrue();
    }

    @Test
    void pageRequestReturnsOnlyTheRequestedVisibleRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        insertTenantUser(jdbc, tenantId, userId, "page-" + userId + "@scope.test");
        insertWorkspaceSession(jdbc, tenantId, userId, workspaceId, sessionId);
        for (int index = 0; index < 12; index += 1) {
            insertRecord(
                    jdbc,
                    UUID.randomUUID(),
                    tenantId,
                    workspaceId,
                    sessionId,
                    userId,
                    "分页记录 " + index,
                    null
            );
        }

        assertThat(recordMapper.selectPageByScope(
                tenantId, workspaceId, null, null, null, 10, 0L)).hasSize(10);
        assertThat(recordMapper.countByScope(tenantId, workspaceId, null, null, null)).isEqualTo(12);
        assertThat(recordMapper.selectPageByScope(
                tenantId, workspaceId, null, null, null, 10, 10L)).hasSize(2);
    }

    private void insertTenantUser(JdbcTemplate jdbc, UUID tenantId, UUID userId, String email) {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, "scope-test");
        jdbc.update(
                """
                INSERT INTO user_account (id, tenant_id, email, display_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                userId,
                tenantId,
                email,
                "Scope Test"
        );
    }

    private void insertWorkspaceSession(
            JdbcTemplate jdbc,
            UUID tenantId,
            UUID userId,
            UUID workspaceId,
            UUID sessionId
    ) {
        jdbc.update(
                """
                INSERT INTO workspace (id, tenant_id, name, created_by, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                workspaceId,
                tenantId,
                "scope workspace",
                userId
        );
        jdbc.update(
                """
                INSERT INTO optimization_session (id, tenant_id, workspace_id, created_by, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                sessionId,
                tenantId,
                workspaceId,
                userId
        );
    }

    private void insertRecord(
            JdbcTemplate jdbc,
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID sessionId,
            UUID userId,
            String rawPrompt,
            OffsetDateTime deletedAt
    ) {
        jdbc.update(
                """
                INSERT INTO optimization_record (
                    id, tenant_id, workspace_id, session_id, created_by, template_code,
                    raw_prompt, optimized_prompt, result_metadata, permission_policy, deleted_at
                ) VALUES (?, ?, ?, ?, ?, 'AUTO', ?, ?, '{}'::jsonb, '{}'::jsonb, ?)
                """,
                id,
                tenantId,
                workspaceId,
                sessionId,
                userId,
                rawPrompt,
                "optimized",
                deletedAt
        );
    }
}
