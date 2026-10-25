package com.promptoptimizer.context.domain;

import java.util.List;

/**
 * 上下文视觉识别的预览、明确确认和页级覆盖协议；不保存原图或供应商文件标识。
 * 坐标相对于已校正方向的预览，范围为 0..1，由服务端重新施加到实际外发像素。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ContextVisionModels {
    private ContextVisionModels() { }

    /** 文字层是否可读与外发拦截分开记录；建议识别不构成用户授权。 */
    public record PageInfo(int page, boolean readableText, boolean hasImages, boolean suggested, boolean blocked, String reason) { }
    /** 仅在等待用户确认期间提供预览；模型和页数限制来自本次运行配置。 */
    public record Review(List<PageInfo> pages, int maxPages, int maxCalls, int maxOutputTokens, int deadlineSeconds) { }
    /** 服务端应用不透明遮挡；拒绝越界、空或非有限坐标。 */
    public record Mask(double x, double y, double width, double height) { }
    /** 页码从 1 开始，不接受文件路径、外部 URL 或客户端声明的身份。 */
    public record PageSelection(int page, List<Mask> masks) { }
    /** 空 pages 表示只在本地提取；非空时必须有明确模型及本次外发同意。 */
    public record Confirmation(String modelId, boolean consent, List<PageSelection> pages) { }
    /** 状态区分文字层、视觉识别、未识别及失败，不能以已有少量正文证明全文完整。 */
    public record PageCoverage(int page, PageMethod method, PageStatus status, String message) { }
    /** TEXT_LAYER 是本地文字层，VISION 是已调用模型，NONE 表示没有识别正文。 */
    public enum PageMethod { TEXT_LAYER, VISION, NONE }
    /** COMPLETE 只表示处理完成；PARTIAL/SKIPPED/FAILED 不得展示为全文已读，均需保留遗漏说明。 */
    public enum PageStatus { COMPLETE, PARTIAL, SKIPPED, FAILED }
    /** 转录和解释分别交付；模型自评不作为正确性的证明。 */
    public record Recognition(String text, List<String> tables, String description, List<String> uncertain,
                              boolean complete, boolean mock) { }
    /** 公共目录的视觉准入投影，与模型管理写入的是同一份能力登记。 */
    public record Model(String id, String displayName, boolean vision, int maxImages, String evidence, boolean mock) { }
}
