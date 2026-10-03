package com.promptoptimizer.analytics.infrastructure;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 从异常链提取安全 SQLState 与约束名称，不读取可能带有凭据或行数据的 JDBC 消息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
record AuditFailureDiagnostics(String sqlState, String constraint) {
    /** 数据或结构不变时快速重试无效；延后重放但不丢弃，也不把缺失外键改成空用户。 */
    boolean requiresSlowRetry(Throwable failure) {
        return sqlState.startsWith("22") || sqlState.startsWith("23") || sqlState.startsWith("42")
                || failure instanceof org.springframework.dao.DataIntegrityViolationException
                || failure instanceof org.springframework.dao.InvalidDataAccessResourceUsageException;
    }

    /** 用固定说明帮助定位；数据库原始 detail 可能包含账号、行数据或凭据，不能直接输出。 */
    String explanation() {
        return switch (sqlState) {
            case "23503" -> "审计引用的租户或账号不存在；核对事件来源与当前数据库，保留原事件等待修复。";
            case "23502", "23514" -> "审计字段违反非空或检查约束；核对 Mapper 与实际表结构。";
            case "42P01", "42703" -> "审计表或字段不存在；核对连接目标与已应用迁移版本。";
            case "42501" -> "数据库账号缺少审计写入权限。";
            default -> "审计写入失败；根据异常类型、SQLState 和约束名称检查，原事件保留待重试。";
        };
    }

    /** 分行保留异常类型与项目调用位置；不访问任意异常消息或 suppressed 内容。 */
    static String safeTrace(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var lines = new ArrayList<String>();
        for (Throwable current = failure; current != null && seen.size() < 8 && seen.add(current);
             current = current.getCause()) {
            lines.add("cause[" + (seen.size() - 1) + "] " + current.getClass().getName());
            Arrays.stream(current.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("com.promptoptimizer."))
                    .limit(6)
                    .map(frame -> "  at " + frame.getClassName() + "." + frame.getMethodName()
                            + "(" + frame.getLineNumber() + ")")
                    .forEach(lines::add);
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** 有界遍历 cause，避免异常环；PostgreSQL Driver 是运行时依赖，约束名通过窄反射读取。 */
    static AuditFailureDiagnostics from(Throwable failure) {
        Throwable current = failure;
        String state = "unavailable";
        String constraint = "unavailable";
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql) {
                if (sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) state = sql.getSQLState();
                String name = postgresConstraint(sql);
                if (name != null) constraint = name;
                return new AuditFailureDiagnostics(state, constraint);
            }
            if (current.getCause() == current) break;
        }
        return new AuditFailureDiagnostics(state, constraint);
    }

    /** 只读取受名称白名单保护的约束代码，不输出 PostgreSQL detail 或绑定值。 */
    private static String postgresConstraint(SQLException sql) {
        if (!"org.postgresql.util.PSQLException".equals(sql.getClass().getName())) return null;
        try {
            Object server = sql.getClass().getMethod("getServerErrorMessage").invoke(sql);
            if (server == null) return null;
            Object value = server.getClass().getMethod("getConstraint").invoke(server);
            return value instanceof String name && name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") ? name : null;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }
}
