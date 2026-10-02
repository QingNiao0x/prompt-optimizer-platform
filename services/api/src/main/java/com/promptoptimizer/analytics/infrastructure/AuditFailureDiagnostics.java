package com.promptoptimizer.analytics.infrastructure;

import java.sql.SQLException;

/**
 * 从异常链提取安全 SQLState 与约束名称，不读取可能带有凭据或行数据的 JDBC 消息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
record AuditFailureDiagnostics(String sqlState, String constraint) {
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
