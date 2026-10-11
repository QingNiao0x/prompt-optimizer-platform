package com.promptoptimizer.analytics.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.TokenRank;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.UUID;

/**
 * audit_event 中 MODEL_CALL_RECORDED 的只读映射；actor_user_id 必须为空。
 * JSON 仅投影对账白名单，查询不连接身份表；管理员全局或显式租户范围由应用服务限定。
 */
@Mapper
public interface ModelCallAnalyticsMapper {
    /** 聚合实际或未知传输尝试，排除 Mock 与明确未发出的请求，包含失败时的有效用量。 */
    List<TokenRank> tokenRanking(@Param("period") AnalyticsPeriod period, @Param("tenantId") UUID tenantId,
                                @Param("groupBy") String groupBy, @Param("limit") int limit);

    /** 分页查看全部尝试，包括 Mock/本地拒绝；不返回完整 JSON 或具体用户身份。 */
    IPage<ModelCallView> selectModelCalls(Page<ModelCallView> page, @Param("period") AnalyticsPeriod period,
                                        @Param("tenantId") UUID tenantId, @Param("operation") String operation,
                                        @Param("requestId") String requestId);

    /** 与明细相同过滤条件的精确计数，避免复杂投影的自动计数改写。 */
    long countModelCalls(@Param("period") AnalyticsPeriod period, @Param("tenantId") UUID tenantId,
                        @Param("operation") String operation, @Param("requestId") String requestId);
}
