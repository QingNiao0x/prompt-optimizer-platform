package com.promptoptimizer.imageprompt.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptRecordRow;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.VisionCapability;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.UUID;

/**
 * 图像记录与视觉准入配置的 XML 持久化接口。所有业务记录操作强制传入服务端完整身份范围。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface ImagePromptMapper {
    /** 按生成操作 UUID 幂等插入，不覆盖用户已经编辑或逻辑删除的记录。 */
    int insertRecord(@Param("r") ImagePromptRecordRow row, @Param("mode") String mode);
    /** 用户、租户和工作区范围内读取未删除记录。 */
    ImagePromptRecordRow find(@Param("id") UUID id, @Param("a") ActorIdentity actor);
    /** 分页在 SQL 层完成，mode 仅绑定枚举名称。 */
    IPage<ImagePromptRecordRow> list(@Param("page") IPage<ImagePromptRecordRow> page,
                                    @Param("a") ActorIdentity actor, @Param("mode") String mode);
    /** 修订号与逻辑删除条件一起校验，避免迟到编辑覆盖其他标签页。 */
    int edit(@Param("id") UUID id, @Param("a") ActorIdentity actor,
             @Param("revision") long revision, @Param("text") String text);
    /** 仅逻辑删除当前身份可访问的业务记录。 */
    int delete(@Param("id") UUID id, @Param("a") ActorIdentity actor);
    /** 管理员配置能力；未登记的模型默认不能接收图片。 */
    List<VisionCapability> capabilities();
    /** 配置以 public_id 关联现有目录，不保存上游地址或密钥。 */
    int setCapability(@Param("c") VisionCapability capability);
}
