package com.promptoptimizer.textpolish.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.textpolish.domain.TextPolishRecordRow;
import org.apache.ibatis.annotations.*;
import java.util.UUID;

/**
 * 独立润色记录及模块模型策略的XML持久化入口；业务记录一律绑定完整服务端身份。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface TextPolishMapper {
    /** 操作主键幂等插入；删除记录不得复活。 */
    int insertRecord(@Param("r") TextPolishRecordRow row);
    /** 排除逻辑删除和其他身份。 */
    TextPolishRecordRow find(@Param("id") UUID id, @Param("a") ActorIdentity actor);
    /** 真实SQL分页，按生成时间稳定排序。 */
    IPage<TextPolishRecordRow> list(@Param("page") IPage<TextPolishRecordRow> page, @Param("a") ActorIdentity actor);
    /** 修订号、范围、删除状态在同一写语句中检查。 */
    int edit(@Param("id") UUID id, @Param("a") ActorIdentity actor, @Param("revision") long revision, @Param("text") String text);
    /** 用户业务删除仅更新时间标志。 */
    int delete(@Param("id") UUID id, @Param("a") ActorIdentity actor);
    /** 模块策略以JSON读取，不含端点或密钥。 */
    String policy();
    /** 单语句原子更新完整策略，默认模型和启用清单不会分步暴露。 */
    int setPolicy(@Param("ids") String ids, @Param("defaultId") String defaultId);
}
