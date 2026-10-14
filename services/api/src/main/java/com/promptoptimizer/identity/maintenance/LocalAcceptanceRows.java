package com.promptoptimizer.identity.maintenance;

import java.util.Map;

/** 本机维护 SQL 的类型化结果；仅描述行结构，不开放账户合并业务接口。
 * @author QingNiao
 * @since 0.1.0
 */
public final class LocalAcceptanceRows {
    /** 普通合成账户及其历史和共享范围计数，不含登录键或密码。 */
    public record Candidate(String id, String tenantId, String status, long records, long visible,
                            long workspaces, long otherMembers, long otherUsers) {
        /** 仅供本机维护报告使用的非敏感计数视图。 */
        public Map<String, Object> asMap() {
            return Map.of("id", id, "tenant_id", tenantId, "status", status, "records", records,
                    "visible", visible, "workspaces", workspaces, "other_members", otherMembers, "other_users", otherUsers);
        }
    }
    /** 私有恢复快照：表名和原行 JSON，账户快照排除密码哈希和登录键。 */
    public record Backup(String kind, String data) { }
}
