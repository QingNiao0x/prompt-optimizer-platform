package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.context.domain.ContextVisionModels.*;
import com.promptoptimizer.context.infrastructure.vision.ContextVisionProperties;
import com.promptoptimizer.context.service.ContextVisionProvider;
import com.promptoptimizer.context.service.DocumentUploadException;
import com.promptoptimizer.provider.domain.ProviderException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 上下文视觉资料的本地审阅与已授权提取。上传不自动外发；逐页保留文字层或明确选中的视觉结果。
 * 页码、遮挡、调用次数、输出及时间预算在此统一校验，失败保留其他已成功页面并报告遗漏。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ContextVisualDocumentExtractor {
    private final ContextPageRasterizer rasterizer;
    private final ContextVisionProvider provider;
    private final ContextVisionModelCatalog models;
    private final ContextVisionProperties limits;
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    public ContextVisualDocumentExtractor(ContextPageRasterizer rasterizer, ContextVisionProvider provider,
                                         ContextVisionModelCatalog models, ContextVisionProperties limits) {
        this.rasterizer = rasterizer; this.provider = provider; this.models = models; this.limits = limits;
    }
    /** 本期仅为 JPEG/PNG 和 PDF 开放视觉审阅，其他类型沿用原解析链路。 */
    public boolean supports(String language) { return Set.of("jpeg", "jpg", "png", "pdf").contains(language); }
    /** 只返回公开模型目录及准入，不包含供应商密钥。 */
    public List<Model> models() { return models.models(); }

    /** 只做本地检查，不调用摘要、Embedding 或视觉模型，不在审阅协议中回显原始文字。 */
    public Review inspect(Path source, String language) throws IOException {
        List<PageInfo> pages = new ArrayList<>();
        if ("pdf".equals(language)) {
            try (PDDocument document = open(source)) {
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    checkCancelled(() -> false);
                    var text = text(document, page);
                    var pdfPage = document.getPage(page - 1);
                    boolean readable = readable(text.value()) && !text.truncated();
                    boolean blocked = scanner.containsCredential(text.value());
                    boolean hasImages = false;
                    var resources = pdfPage.getResources();
                    if (resources != null) for (var name : resources.getXObjectNames()) {
                        if (resources.isImageXObject(name)) { hasImages = true; break; }
                    }
                    boolean suggested = !readable && pdfPage.hasContents();
                    pages.add(new PageInfo(page, readable, hasImages, suggested, blocked, blocked
                            ? "文字层含疑似凭据，该页禁止外发；请在本地遮挡原文件后重新上传。"
                            : text.truncated() ? "文字层超过单页安全长度，可选择视觉识别或使用拆分文件。"
                            : suggested ? "文字层不足，建议检查预览并识别。"
                            : readable ? "可本地提取文字；如需理解图表，可明确选择此页。" : "空白页，无需识别。"));
                }
            }
        } else {
            rasterizer.png(() -> rasterizer.image(source), List.of());
            pages.add(new PageInfo(1, false, true, true, false, "图片需要视觉识别；请先预览并遮挡敏感区域。"));
        }
        return new Review(List.copyOf(pages), limits.getMaxPages(), limits.getMaxCalls(), limits.getMaxOutputTokens(), limits.getDeadlineSeconds());
    }

    /** 私有预览只返回规范化像素；是否仍允许读取由上传会话在调用前后校验。 */
    public byte[] preview(Path source, String language, int page) throws IOException {
        if ("pdf".equals(language)) {
            try (var document = open(source)) {
                if (page < 1 || page > document.getNumberOfPages()) throw invalid("预览页码无效。");
                return rasterizer.png(() -> rasterizer.pdf(document, page), List.of());
            }
        }
        if (page != 1) throw invalid("图片只有一页。");
        return rasterizer.png(() -> rasterizer.image(source), List.of());
    }

    /** 确认只接受本次实际存在的页码；非空选择必须有视觉模型与显式外发同意。 */
    public Confirmation validate(Review review, Confirmation input) {
        if (input == null || input.pages() == null || input.pages().size() > Math.min(limits.getMaxPages(), limits.getMaxCalls()))
            throw invalid("识别页数超过本次任务预算。");
        if (!input.pages().isEmpty()) {
            if (!input.consent()) throw invalid("请先确认图片隐私及发送至所选模型服务。");
            models.require(input.modelId());
        }
        Set<Integer> seen = new HashSet<>();
        List<PageSelection> pages = new ArrayList<>();
        for (PageSelection selection : input.pages()) {
            if (selection == null || !seen.add(selection.page())) throw invalid("识别页码重复或无效。");
            var info = review.pages().stream().filter(page -> page.page() == selection.page()).findFirst().orElseThrow(() -> invalid("识别页码超出文档范围。"));
            if (info.blocked()) throw invalid("所选页含疑似凭据，请先处理原文件；不能直接发送该页。");
            List<Mask> masks = selection.masks() == null ? List.of() : selection.masks();
            if (masks.size() > 30) throw invalid("单页最多设置 30 个遮挡区域。");
            for (Mask mask : masks) {
                if (mask == null || !Double.isFinite(mask.x()) || !Double.isFinite(mask.y()) || !Double.isFinite(mask.width()) || !Double.isFinite(mask.height())
                        || mask.x() < 0 || mask.y() < 0 || mask.width() <= 0 || mask.height() <= 0 || mask.x() + mask.width() > 1 || mask.y() + mask.height() > 1)
                    throw invalid("遮挡区域必须位于页面内部。");
            }
            pages.add(new PageSelection(selection.page(), List.copyOf(masks)));
        }
        return new Confirmation(input.modelId(), input.consent(), List.copyOf(pages));
    }

    /** 只对明确选中的页发送像素；取消、上游失败或到达预算后不再发起其他视觉调用。 */
    public Result extract(Path source, String language, Review review, Confirmation input,
                          StreamingDocumentExtractor.SectionConsumer consumer, StreamingDocumentExtractor.ProgressListener progress,
                          BooleanSupplier cancelled) throws IOException {
        var confirmation = validate(review, input);
        Map<Integer, PageSelection> selected = new HashMap<>();
        confirmation.pages().forEach(page -> selected.put(page.page(), page));
        List<PageCoverage> coverage = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long deadline = System.nanoTime() + limits.getDeadlineSeconds() * 1_000_000_000L;
        long[] characters = {0};
        int[] sections = {0};
        StreamingDocumentExtractor.SectionConsumer safeConsumer = (label, value) -> {
            checkCancelled(cancelled);
            String safe = scanner.redact(value);
            if (!Objects.equals(safe, value)) add(warnings, "已过滤识别或文字层中的疑似凭据；请人工核对资料。");
            if (safe != null && !safe.isBlank()) { consumer.accept(label, safe); characters[0] += safe.length(); sections[0]++; }
        };
        try (PDDocument document = "pdf".equals(language) ? open(source) : null) {
            int calls = 0;
            boolean halted = false;
            for (PageInfo page : review.pages()) {
                checkCancelled(cancelled);
                int number = page.page();
                PageText nativeText = document == null ? new PageText("", false) : text(document, number);
                PageSelection selection = selected.get(number);
                if (selection == null) {
                    if (!nativeText.value().isBlank()) {
                        safeConsumer.accept("第 " + number + " 页 · 文字层", nativeText.value());
                        coverage.add(new PageCoverage(number, PageMethod.TEXT_LAYER, nativeText.truncated() || page.suggested() ? PageStatus.PARTIAL : PageStatus.COMPLETE,
                                nativeText.truncated() ? "单页文字层达到长度上限" : page.suggested() ? "仅提取少量文字，该页画面未识别" : "文字层已提取；未调用视觉模型"));
                    } else {
                        boolean blank = !page.suggested() && !page.hasImages();
                        coverage.add(new PageCoverage(number, PageMethod.NONE, blank ? PageStatus.COMPLETE : PageStatus.SKIPPED, blank ? "空白页" : "未选择识别此页"));
                    }
                } else if (halted || calls >= limits.getMaxCalls() || System.nanoTime() >= deadline) {
                    coverage.add(new PageCoverage(number, PageMethod.NONE, PageStatus.SKIPPED, "已停止后续视觉调用或达到任务预算"));
                } else {
                    try {
                        models.require(confirmation.modelId());
                        byte[] png = document == null ? rasterizer.png(() -> rasterizer.image(source), selection.masks())
                                : rasterizer.png(() -> rasterizer.pdf(document, number), selection.masks());
                        checkCancelled(cancelled);
                        if (System.nanoTime() >= deadline) throw invalid("已达到任务时间预算。");
                        calls++;
                        Recognition result = provider.recognize(confirmation.modelId(), number, png, limits.getMaxOutputTokens());
                        checkCancelled(cancelled);
                        // 掩码页不再加入原文字层，防止被遮挡的信息经另一条输入路径重新进入上下文。
                        safeConsumer.accept("第 " + number + " 页 · 视觉原文转录", result.text());
                        for (String table : result.tables()) safeConsumer.accept("第 " + number + " 页 · 视觉表格转录", table);
                        safeConsumer.accept("第 " + number + " 页 · 视觉描述（模型解释，需核对）", result.description());
                        for (String uncertain : result.uncertain()) add(warnings, "第 " + number + " 页待核对：" + scanner.redact(uncertain));
                        if (result.mock()) add(warnings, "本机演示未执行真实视觉识别。");
                        coverage.add(new PageCoverage(number, PageMethod.VISION, result.complete() && !result.mock() ? PageStatus.COMPLETE : PageStatus.PARTIAL,
                                result.complete() && !result.mock() ? "模型已返回该页结果，请人工核对" : "模型未确认完整识别，存在需核对内容"));
                    } catch (ProviderException | DocumentUploadException exception) {
                        checkCancelled(cancelled);
                        halted = true;
                        coverage.add(new PageCoverage(number, PageMethod.VISION, PageStatus.FAILED, "该页识别失败；未自动重试"));
                        add(warnings, "第 " + number + " 页：" + exception.getMessage());
                        if (selection.masks().isEmpty() && !nativeText.value().isBlank()) safeConsumer.accept("第 " + number + " 页 · 仅文字层回退", nativeText.value());
                    }
                }
                progress.onProgress(number, review.pages().size());
            }
        }
        List<Integer> omitted = coverage.stream().filter(page -> page.status() != PageStatus.COMPLETE).map(PageCoverage::page).toList();
        if (!omitted.isEmpty()) add(warnings, "部分页面未完整识别：" + omitted + "；已保留其他成功内容，不代表全文已读。");
        return new Result(new StreamingDocumentExtractor.ExtractionReport(sections[0], characters[0], omitted.isEmpty(), List.copyOf(warnings)), List.copyOf(coverage));
    }

    /** 拒绝超出审阅页数或无提取权限的 PDF，失败时释放 PDFBox 资源。 */
    private PDDocument open(Path source) throws IOException {
        PDDocument document = Loader.loadPDF(source.toFile());
        if (!document.getCurrentAccessPermission().canExtractContent() || document.getNumberOfPages() < 1
                || document.getNumberOfPages() > limits.getMaxDocumentPages()) {
            document.close();
            throw invalid("PDF 不允许提取内容或超过视觉审阅页数上限（" + limits.getMaxDocumentPages() + " 页），请先处理或拆分文件。");
        }
        return document;
    }
    /** 单页最多保留十万字符；超长文字层不能以截断后的少量正文标为完整。 */
    private PageText text(PDDocument document, int page) throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true); stripper.setStartPage(page); stripper.setEndPage(page);
        var writer = new BoundedWriter();
        stripper.writeText(document, writer);
        return new PageText(writer.text.toString(), writer.truncated);
    }
    /** 字符密度和乱码比例仅用于建议选页，不构成准确率证明或自动外发授权。 */
    private boolean readable(String text) {
        long meaningful = text.codePoints().filter(Character::isLetterOrDigit).count();
        return meaningful >= 12 && text.codePoints().filter(value -> value == 0xfffd || value == 0).count() < Math.max(1, text.length() / 50);
    }
    private void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw invalid("文档处理已取消。");
    }
    private void add(List<String> values, String value) { if (!values.contains(value) && values.size() < 100) values.add(value); }
    private DocumentUploadException invalid(String message) { return new DocumentUploadException(DocumentUploadException.Reason.INVALID_ARGUMENT, message); }
    /** 提取报告和逐页覆盖一起返回，供同一上传会话展示与检索。 */
    public record Result(StreamingDocumentExtractor.ExtractionReport extraction, List<PageCoverage> pages) { }
    private record PageText(String value, boolean truncated) { }
    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;
        @Override public void write(char[] value, int offset, int length) {
            int accepted = Math.min(length, 100_000 - text.length());
            if (accepted > 0) text.append(value, offset, accepted);
            truncated |= accepted < length;
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
