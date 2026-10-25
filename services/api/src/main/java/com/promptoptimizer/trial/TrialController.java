package com.promptoptimizer.trial;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.service.impl.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests;
import com.promptoptimizer.imageprompt.service.*;
import com.promptoptimizer.provider.dto.AvailableModel;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.textpolish.domain.TextPolishModels;
import com.promptoptimizer.textpolish.service.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 独立受限访客协议：无历史、无Plan、无账号角色；全部生成经过服务端会话与原子额度。
 * 正式接口不放宽权限，CSRF继续由现有SecurityFilterChain验证。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/trial")
public class TrialController {
    private final TrialService trials;
    private final NaturalEnhancementService natural;
    private final DefaultEnhancementOrchestrator structured;
    private final PlatformModelCatalog catalog;
    private final ImagePromptService images;
    private final ImagePromptAssetService assets;
    private final ImagePromptModelService imageModels;
    private final TextPolishService polish;
    private final TextPolishModelService polishModels;
    public TrialController(TrialService trials, NaturalEnhancementService natural, DefaultEnhancementOrchestrator structured,
            PlatformModelCatalog catalog, ImagePromptService images, ImagePromptAssetService assets,
            ImagePromptModelService imageModels, TextPolishService polish, TextPolishModelService polishModels) {
        this.trials = trials; this.natural = natural; this.structured = structured; this.catalog = catalog;
        this.images = images; this.assets = assets; this.imageModels = imageModels; this.polish = polish; this.polishModels = polishModels;
    }
    /** 查询额度不创建身份；页面使用此结果而不是localStorage作为计数依据。 */
    @GetMapping("/status") public ApiResponse<TrialService.Status> status(HttpServletRequest r) { return ok(r, trials.status(r)); }
    /** 浏览器先取得CSRF，再显式建立试用会话。 */
    @PostMapping("/session") public ApiResponse<TrialService.Status> start(HttpServletRequest r, HttpServletResponse s) { return ok(r, trials.start(r, s)); }
    /** 可选目录仍由平台管理员启停，提交时核心服务再次核验当前模型。 */
    @GetMapping("/models") public ApiResponse<List<AvailableModel>> models(HttpServletRequest r) {
        trials.guest(r);
        return ok(r, catalog.available().stream().map(m -> new AvailableModel(m.publicId(), m.displayName(), m.routeKey(), m.defaultModel())).toList());
    }
    /** 只开放直接增强，不允许借试用引用正式Plan或持久化文件索引。 */
    @PostMapping("/optimizations") public ApiResponse<PromptDocument> enhance(@Valid @RequestBody OptimizationRequest input,
            @RequestHeader("X-Trial-Request-Id") UUID operation, HttpServletRequest r) {
        trials.guest(r);
        if (input.planConfirmation() != null) throw new TrialException(401, "AUTHENTICATION_REQUIRED", "请注册登录后使用Plan。");
        if (!input.context().files().isEmpty()) throw new TrialException(401, "AUTHENTICATION_REQUIRED", "免费体验支持文字输入，请注册登录后使用项目文件。");
        catalog.resolve(input.modelId());
        return ok(r, trials.execute(r, TrialFeature.ENHANCEMENT, operation, input, PromptDocument.class,
                () -> input.enhancement().promptFormat() == PromptFormat.STRUCTURED
                        ? PromptDocument.structured(structured.optimizeTrial(input)) : natural.optimizeTrial(input)));
    }
    /** 返回短期优化结果，不读正式历史。 */
    @GetMapping("/optimizations/operations/{id}") public ApiResponse<TrialService.Operation<PromptDocument>> enhancementOperation(@PathVariable UUID id, HttpServletRequest r) {
        return ok(r, trials.operation(r, TrialFeature.ENHANCEMENT, id, PromptDocument.class));
    }
    /** 润色可用模型继续服从模块准入；访客不提供云端历史保存。 */
    @GetMapping("/text-polish/capabilities") public ApiResponse<TextPolishModels.Capabilities> polishCapabilities(HttpServletRequest r) {
        trials.guest(r); return ok(r, new TextPolishModels.Capabilities(polishModels.models(), 12000, 24000, false));
    }
    /** 仅RAW原文可试用，来源历史或版本参数在扣额前拒绝。 */
    @PostMapping("/text-polish") public ApiResponse<TextPolishModels.Document> polish(@Valid @RequestBody TextPolishModels.Generate input, HttpServletRequest r) {
        trials.guest(r); polish.validateTrial(input);
        return ok(r, trials.execute(r, TrialFeature.TEXT_POLISH, input.requestId(), input, TextPolishModels.Document.class, () -> polish.generateTrial(input)));
    }
    /** 同一访客恢复原件，不重复消费或再次调用模型。 */
    @GetMapping("/text-polish/operations/{id}") public ApiResponse<TrialService.Operation<TextPolishModels.Document>> polishOperation(@PathVariable UUID id, HttpServletRequest r) {
        return ok(r, trials.operation(r, TrialFeature.TEXT_POLISH, id, TextPolishModels.Document.class));
    }
    /** 图像只开放单图还原，原图仍经过现有解码/EXIF/大小保护。 */
    @GetMapping("/image-prompts/capabilities") public ApiResponse<ImagePromptModels.Capabilities> imageCapabilities(HttpServletRequest r) {
        trials.guest(r); return ok(r, new ImagePromptModels.Capabilities(imageModels.models(), ImagePromptAssetService.MAX_BYTES,
                ImagePromptAssetService.MAX_PIXELS, ImagePromptAssetService.MAX_EDGE, 1, ImagePromptAssetService.TTL.toSeconds(), false));
    }
    /** 上传属于服务器访客凭证，额度耗尽后拒绝继续占用磁盘。 */
    @PostMapping(value = "/image-prompt-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImagePromptModels.Asset> upload(@RequestPart("file") MultipartFile file, HttpServletRequest r) {
        String guest = trials.guest(r); trials.claimUpload(guest);
        return ok(r, assets.uploadGuest(guest, file));
    }
    /** 私有预览禁用缓存，不允许资源编号跨访客使用。 */
    @GetMapping("/image-prompt-assets/{id}") public ResponseEntity<byte[]> preview(@PathVariable UUID id, HttpServletRequest r) {
        String guest = trials.guest(r); var asset = assets.requireGuest(guest, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(asset.mimeType())).body(assets.bytesGuest(guest, id));
    }
    /** 删除仅撤销当前访客临时图片，不涉及任何业务历史。 */
    @DeleteMapping("/image-prompt-assets/{id}") public ApiResponse<Void> revoke(@PathVariable UUID id, HttpServletRequest r) {
        assets.revokeGuest(trials.guest(r), id); return ok(r, null);
    }
    /** 单次图像生成在额度预占后复用相同的真实识图实现。 */
    @PostMapping("/image-prompts") public ApiResponse<ImagePromptModels.Document> image(@Valid @RequestBody ImagePromptRequests.Generate input, HttpServletRequest r) {
        String guest = trials.guest(r); images.validateTrial(guest, input);
        return ok(r, trials.execute(r, TrialFeature.IMAGE_PROMPT, input.requestId(), input, ImagePromptModels.Document.class,
                () -> images.extractTrial(guest, input)));
    }
    /** 操作恢复不等于历史权限，仅返回当前访客同一次生成的短期原件。 */
    @GetMapping("/image-prompts/operations/{id}") public ApiResponse<TrialService.Operation<ImagePromptModels.Document>> imageOperation(@PathVariable UUID id, HttpServletRequest r) {
        return ok(r, trials.operation(r, TrialFeature.IMAGE_PROMPT, id, ImagePromptModels.Document.class));
    }
    private <T> ApiResponse<T> ok(HttpServletRequest request, T value) { return ApiResponse.success((String)request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), value); }
}
