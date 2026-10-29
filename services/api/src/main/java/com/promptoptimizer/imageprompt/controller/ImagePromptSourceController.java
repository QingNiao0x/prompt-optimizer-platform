package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.*;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests;
import com.promptoptimizer.imageprompt.service.ImagePromptSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

/**
 * 资料的本地解析、私有审阅与显式模型授权；读取、撤销及授权始终使用完整服务端身份。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/image-prompt-sources")
public class ImagePromptSourceController {
    private final ImagePromptSourceService sources;
    private final CurrentActor current;
    public ImagePromptSourceController(ImagePromptSourceService sources, CurrentActor current) { this.sources = sources; this.current = current; }
    /** 返回可选格式与实际预算。 */
    @GetMapping("/capabilities")
    public ApiResponse<Capabilities> capabilities(HttpServletRequest request) { current.require(); return ok(request, sources.capabilities()); }
    /** 主动上传原件，仅本地提取后立即清理原件。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Asset> upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) { return ok(request, sources.upload(current.require(), file)); }
    /** 接收粘贴内容或用户主动选择的文本文件集合，不接受本机读取路径。 */
    @PostMapping("/text")
    public ApiResponse<Asset> text(@Valid @RequestBody TextUpload input, HttpServletRequest request) { return ok(request, sources.text(current.require(), input)); }
    /** 获取当前身份的脱敏片段预览。 */
    @GetMapping("/{id}")
    public ApiResponse<Asset> detail(@PathVariable UUID id, HttpServletRequest request) { return ok(request, sources.require(current.require(), id)); }
    /** 撤销临时引用，不删除既有优化历史。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable UUID id, HttpServletRequest request) { sources.revoke(current.require(), id); return ok(request, null); }
    /** 用户显式确认完整范围、用途和模型后登记不可篡改的短期同意。 */
    @PostMapping("/consents")
    public ApiResponse<Consent> consent(@Valid @RequestBody ImagePromptRequests.Task input, HttpServletRequest request) { return ok(request, sources.consent(current.require(), input.input())); }
    private <T> ApiResponse<T> ok(HttpServletRequest request, T value) { return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), value); }
}
