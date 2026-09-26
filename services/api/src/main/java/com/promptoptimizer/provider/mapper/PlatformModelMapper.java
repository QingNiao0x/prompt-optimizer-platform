package com.promptoptimizer.provider.mapper;

import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 通过 resources/mapper/provider/PlatformModelMapper.xml 访问平台模型目录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface PlatformModelMapper {

    /** 查询未逻辑删除的模型目录。 */
    List<ModelEntry> selectAllActive();

    /** 按公开标识查询未逻辑删除的目录项。 */
    ModelEntry selectActiveByPublicId(@Param("publicId") String publicId);

    /** 按主键查询未逻辑删除的目录项。 */
    ModelEntry selectByIdActive(@Param("id") UUID id);

    /** 查找可恢复的逻辑删除目录项主键。 */
    UUID selectDeletedId(@Param("publicId") String publicId);

    /** 检查是否已有启用的默认模型。 */
    boolean hasEnabledDefault();

    /** 统计全部目录行，包括逻辑删除行，用于保持初始化幂等。 */
    int countAll();

    /** 清除当前有效目录项的默认标记。 */
    int clearDefault();

    /** 恢复已逻辑删除模型并更新可编辑字段。 */
    int restore(@Param("id") UUID id, @Param("displayName") String displayName,
                @Param("enabled") boolean enabled, @Param("defaultModel") boolean defaultModel,
                @Param("sortOrder") int sortOrder);

    /** 首次启动时按公开 ID 幂等导入部署配置中的模型。 */
    int insertIfAbsent(@Param("id") UUID id, @Param("publicId") String publicId,
                       @Param("routeKey") String routeKey, @Param("upstreamModel") String upstreamModel,
                       @Param("displayName") String displayName, @Param("enabled") boolean enabled,
                       @Param("defaultModel") boolean defaultModel, @Param("sortOrder") int sortOrder);

    /** 新建管理员维护的目录项。 */
    int insert(@Param("id") UUID id, @Param("publicId") String publicId,
               @Param("routeKey") String routeKey, @Param("upstreamModel") String upstreamModel,
               @Param("displayName") String displayName, @Param("enabled") boolean enabled,
               @Param("defaultModel") boolean defaultModel, @Param("sortOrder") int sortOrder);

    /** 更新模型目录的展示与状态字段。 */
    int update(@Param("id") UUID id, @Param("displayName") String displayName,
               @Param("enabled") boolean enabled, @Param("defaultModel") boolean defaultModel,
               @Param("sortOrder") int sortOrder);

    /** 逻辑删除模型目录项。 */
    int softDelete(@Param("id") UUID id);
}
