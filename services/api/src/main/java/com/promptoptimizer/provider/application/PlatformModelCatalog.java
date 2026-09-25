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

    record ModelEntry(UUID id, String publicId, String routeKey, String upstreamModel,
                      String displayName, boolean enabled, boolean defaultModel, int sortOrder) {
    }

    record RouteEntry(String key, String providerName) {
    }

    record ModelChange(String routeKey, String upstreamModel, String displayName,
                       boolean enabled, boolean defaultModel, int sortOrder) {
    }
}
