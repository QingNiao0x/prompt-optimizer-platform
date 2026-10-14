package com.promptoptimizer.localacceptance;

import com.promptoptimizer.identity.maintenance.LocalAcceptanceRows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** 本机维护入口专用映射，不由生产组件扫描注册，也不开放 HTTP 接口。
 * @author QingNiao
 * @since 0.1.0
 */
public interface LocalAcceptanceMaintenanceMapper {
    /** 严格识别普通合成验收账户，不凭展示名称单独决定合并。 */
    List<LocalAcceptanceRows.Candidate> candidates();
    /** 读取可恢复的业务快照；密码哈希不进入备份或报告。 */
    List<LocalAcceptanceRows.Backup> snapshot(@Param("users") List<UUID> users);
    /** 在事务内锁定已核实账户，避免并发合并覆盖。 */
    List<String> lockUsers(@Param("users") List<UUID> users);
    int mergeRecords(@Param("users") List<UUID> users, @Param("user") UUID user,
                     @Param("tenant") UUID tenant, @Param("workspace") UUID workspace);
    int mergeSessions(@Param("users") List<UUID> users, @Param("user") UUID user,
                      @Param("tenant") UUID tenant, @Param("workspace") UUID workspace);
    int disableUsers(@Param("users") List<UUID> users);
    long visibleHistory(@Param("user") UUID user, @Param("tenant") UUID tenant, @Param("workspace") UUID workspace);
}
