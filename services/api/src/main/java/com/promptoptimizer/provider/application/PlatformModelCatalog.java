package com.promptoptimizer.provider.application;

import java.util.List;
import java.util.UUID;

/**
 * 平台模型目录的唯一业务入口；可选范围由管理员维护，供应商密钥留在服务端路由中。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PlatformModelCatalog {

    /** 返回本次请求可以选择的模型，不包含端点和凭据。 */
    List<ModelEntry> available();

    /** 将用户模型标识解析为当前可用模型；空值表示平台默认模型。 */
    ModelEntry resolve(String requestedId);

    /** 返回供管理员编辑的未删除目录项。 */
    List<ModelEntry> all();

    /** 返回已由平台部署环境配置的供应商路由。 */
    List<RouteEntry> routes();

    /** 创建或恢复一个平台模型目录项。 */
    ModelEntry create(ModelChange change);

    /** 修改展示和可用状态，模型路由身份保持不变。 */
    ModelEntry update(UUID id, ModelChange change);

    /** 逻辑删除非默认模型。 */
    void delete(UUID id);

    /**
     * platform_model 表的只读投影；上游凭据和端点由服务端路由配置管理，不属于此记录。
     *
     * @param id 目录项主键
     * @param publicId 终端用户提交的稳定模型标识
     * @param routeKey 服务端 Provider 路由键
     * @param upstreamModel 路由实际调用的上游模型名称
     * @param displayName 用户界面显示名称
     * @param enabled 是否允许用户选择和调用
     * @param defaultModel 是否为平台默认模型
     * @param sortOrder 用户模型列表的非负排序值
     */
    record ModelEntry(UUID id, String publicId, String routeKey, String upstreamModel,
                      String displayName, boolean enabled, boolean defaultModel, int sortOrder) {
    }

    record RouteEntry(String key, String providerName) {
    }

    record ModelChange(String routeKey, String upstreamModel, String displayName,
                       boolean enabled, boolean defaultModel, int sortOrder) {
    }
}
