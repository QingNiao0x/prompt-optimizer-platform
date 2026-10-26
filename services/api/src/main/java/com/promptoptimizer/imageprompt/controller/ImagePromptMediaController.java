package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import com.promptoptimizer.imageprompt.service.ImagePromptMediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

/**
 * 创作素材的私有解析协议；所有身份来自 CurrentActor，上传与预览不会触发模型外发。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/image-prompt-media")
public class ImagePromptMediaController {
    private final ImagePromptMediaService media;
    private final CurrentActor current;

    public ImagePromptMediaController(ImagePromptMediaService media, CurrentActor current) {
        this.media = media; this.current = current;
    }

    /** 返回解析限制与本机解码可用性，模型准入仍由平台统一目录决定。 */
    @GetMapping("/capabilities")
    public ApiResponse<Capabilities> capabilities(HttpServletRequest request) {
        current.require(); return ok(request, media.capabilities());
    }

    /** 视频范围使用源文件毫秒位置，缺省仅选前三十秒；不接受本地路径或外部 URL。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaAsset> upload(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long startMs, @RequestParam(required = false) Long endMs,
            HttpServletRequest request) {
        return ok(request, media.upload(current.require(), file, startMs, endMs));
    }

    /** 轮询当前完整身份下的临时任务，保留部分覆盖和失败原因。 */
    @GetMapping("/{id}")
    public ApiResponse<MediaAsset> status(@PathVariable UUID id, HttpServletRequest request) {
        return ok(request, media.require(current.require(), id));
    }

    /** 只返回无元数据 PNG，禁用浏览器缓存和 MIME 猜测，原件从不作为公开链接提供。 */
    @GetMapping("/{id}/units/{unitId}")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id, @PathVariable UUID unitId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=preview.png")
                .body(media.preview(current.require(), id, unitId));
    }

    /** 用户下载经过相同像素遮挡的页面/帧，用于下游素材附件；本操作不触发视觉模型。 */
    @PostMapping("/{id}/units/{unitId}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @PathVariable UUID unitId, @RequestBody Export input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=material-" + unitId + ".png")
                .body(media.export(current.require(), id, unitId, input.masks()));
    }

    /** 撤销访问并取消任务，历史中的说明与正文依然遵守其独立的逻辑删除规则。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable UUID id, HttpServletRequest request) {
        media.revoke(current.require(), id); return ok(request, null);
    }

    private <T> ApiResponse<T> ok(HttpServletRequest request, T value) {
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), value);
    }
}
