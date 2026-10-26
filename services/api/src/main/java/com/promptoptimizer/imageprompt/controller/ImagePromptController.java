package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.*;
import com.promptoptimizer.imageprompt.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

/**
 * 图像业务协议转换；身份只取 CurrentActor，Controller 不接收所有者或上游凭据。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
public class ImagePromptController {
    private final ImagePromptService service;
    private final ImagePromptAssetService assets;
    private final ImagePromptHistoryService history;
    private final CurrentActor current;
    public ImagePromptController(ImagePromptService service, ImagePromptAssetService assets, ImagePromptHistoryService history, CurrentActor current) {
        this.service = service; this.assets = assets; this.history = history; this.current = current;
    }
    /** 返回服务端实际限制和可用模型，不暴露上游端点或密钥。 */
    @GetMapping("/image-prompts/capabilities")
    public ApiResponse<Capabilities> capabilities(HttpServletRequest request) { return ok(request, service.capabilities()); }
    /** 仅接收主动上传的图片，在当前完整身份下建立短期私有资产。 */
    @PostMapping(value = "/image-prompt-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Asset> upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return ok(request, assets.upload(current.require(), file));
    }
    /** 私有预览禁用缓存；链接不可作为公开图床，失效身份仍由 Spring Security 拒绝。 */
    @GetMapping("/image-prompt-assets/{id}")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id) {
        var actor = current.require(); Asset asset = assets.require(actor, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=reference." + (asset.mimeType().equals("image/png") ? "png" : "jpg"))
                .contentType(MediaType.parseMediaType(asset.mimeType())).body(assets.bytes(actor, id));
    }
    /** 撤销当前身份的临时图片；历史记录另按逻辑删除规则处理。 */
    @DeleteMapping("/image-prompt-assets/{id}")
    public ApiResponse<Void> revoke(@PathVariable UUID id, HttpServletRequest request) { assets.revoke(current.require(), id); return ok(request, null); }
    /** 关闭 Plan 的单图还原入口，不接受新图创作要求。 */
    @PostMapping("/image-prompts")
    public ApiResponse<Document> extract(@Valid @RequestBody Generate input, HttpServletRequest request) { return ok(request, service.extract(input)); }
    /** 为图片、视频或PPT内容创作建立服务端绑定计划，参考与替换素材可为空。 */
    @PostMapping("/image-prompts/plans")
    public ApiResponse<Plan> plan(@Valid @RequestBody Generate input, HttpServletRequest request) { return ok(request, service.plan(input)); }
    /** 只接收服务端原题的实际答案，不能由客户端覆盖计划输入或来源。 */
    @PostMapping("/image-prompts/plans/{id}/confirm")
    public ApiResponse<Document> confirm(@PathVariable UUID id, @Valid @RequestBody Confirm input, HttpServletRequest request) { return ok(request, service.confirm(id, input)); }
    /** 查询已提交操作，断网恢复不会再次触发模型。 */
    @GetMapping("/image-prompts/operations/{id}")
    public ApiResponse<ImagePromptService.Operation> operation(@PathVariable UUID id, HttpServletRequest request) { return ok(request, service.operation(id)); }
    /** 保存已生成但未落库的正文，不重放模型调用。 */
    @PostMapping("/image-prompts/operations/{id}/save")
    public ApiResponse<Document> save(@PathVariable UUID id, HttpServletRequest request) { return ok(request, service.retrySave(id)); }
    /** 在租户、工作区和用户范围内分页读取未删除的图像记录。 */
    @GetMapping("/image-prompts/history")
    public ApiResponse<HistoryPage> list(@RequestParam(defaultValue = "1") int current, @RequestParam(defaultValue = "10") int size,
                                        @RequestParam(required = false) Mode mode, HttpServletRequest request) {
        return ok(request, history.list(this.current.require(), current, size, mode));
    }
    /** 返回当前正文版本；已删除或其他身份的记录返回不存在。 */
    @GetMapping("/image-prompts/history/{id}")
    public ApiResponse<Document> detail(@PathVariable UUID id, HttpServletRequest request) { return ok(request, history.find(current.require(), id)); }
    /** 按预期修订号保存人工编辑，明确凭据在保存前拦截。 */
    @PatchMapping("/image-prompts/history/{id}")
    public ApiResponse<Document> edit(@PathVariable UUID id, @Valid @RequestBody Edit input, HttpServletRequest request) {
        if (new SensitiveValueScanner().containsCredential(input.optimizedPrompt())) throw ImagePromptException.invalid("正文含疑似凭据，请移除后再保存。");
        return ok(request, history.edit(current.require(), id, input.optimizedPrompt(), input.expectedRevision()));
    }
    /** 业务删除只写逻辑删除标记，后续读取及再次优化均不可访问。 */
    @DeleteMapping("/image-prompts/history/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, HttpServletRequest request) { history.delete(current.require(), id); return ok(request, null); }
    /** 从服务端当前正文进行文字修订，不重新读取原图。 */
    @PostMapping("/image-prompts/history/{id}/re-optimize")
    public ApiResponse<Document> rewrite(@PathVariable UUID id, @Valid @RequestBody Rewrite input, HttpServletRequest request) { return ok(request, service.rewrite(id, input)); }
    private <T> ApiResponse<T> ok(HttpServletRequest request, T value) { return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), value); }
}
