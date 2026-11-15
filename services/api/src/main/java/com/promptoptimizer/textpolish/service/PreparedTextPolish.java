package com.promptoptimizer.textpolish.service;

import java.util.List;

/**
 * 单次编辑的不可变装配结果；在首次HTTP请求前冻结，协议修复沿用同一实例。
 * 选择描述实际加载的资源，不表示模型已正确执行编辑要求。
 * @author QingNiao
 * @since 0.1.0
 */
public record PreparedTextPolish(String instruction, Selection selection, List<Resource> resources) {
    /** 复制清单，防止装配后调用方修改资源快照。 */
    public PreparedTextPolish {
        resources = List.copyOf(resources);
    }

    /** 正文语言；UNKNOWN包括证据不足及当前未专门支持的语言。 */
    public enum Language { ZH, EN, MIXED, UNKNOWN }

    /**
     * 服务端选择快照，依据仅为有限代码，不存正文片段。
     * GENERAL表示用途交由编辑模型结合全文判断，绝不从单个行业词推断专用规则。
     */
    public record Selection(String selectorVersion, Language language, String profile, String reason) { }

    /** 随应用发布的资源身份；sha256针对实际发送的规范化UTF-8正文，不含文件头。 */
    public record Resource(String id, String version, String sha256) { }
}
