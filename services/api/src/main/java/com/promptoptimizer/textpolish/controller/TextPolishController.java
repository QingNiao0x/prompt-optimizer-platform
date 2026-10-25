package com.promptoptimizer.textpolish.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * 文章祛AI味协议转换，身份只来自CurrentActor；业务与持久化分别由应用服务负责。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/text-polish")
public class TextPolishController {
    private final TextPolishService service;
    private final TextPolishHistoryService history;
    private final CurrentActor actor;
    public TextPolishController(TextPolishService service, TextPolishHistoryService history, CurrentActor actor) { this.service = service; this.history = history; this.actor = actor; }
    /** 获取当前模型和资源范围。 */
    @GetMapping("/capabilities") public ApiResponse<Capabilities> capabilities(HttpServletRequest r) { return ok(r, service.capabilities()); }
    /** 单次生成或基于明确来源继续润色。 */
    @PostMapping public ApiResponse<Document> generate(@Valid @RequestBody Generate input, HttpServletRequest r) { return ok(r, service.generate(input)); }
    /** 断网恢复不重复调用模型。 */
    @GetMapping("/operations/{id}") public ApiResponse<TextPolishService.Operation> operation(@PathVariable UUID id, HttpServletRequest r) { return ok(r, service.operation(id)); }
    /** 保存已完成的原件，不产生新调用。 */
    @PostMapping("/operations/{id}/save") public ApiResponse<Document> save(@PathVariable UUID id, HttpServletRequest r) { return ok(r, service.save(id)); }
    /** 真实SQL分页私有文章历史。 */
    @GetMapping("/history") public ApiResponse<HistoryPage> list(@RequestParam(defaultValue="1") int current, @RequestParam(defaultValue="10") int size, HttpServletRequest r) { return ok(r, history.list(actor.require(), current, size)); }
    /** 同范围未删除记录详情。 */
    @GetMapping("/history/{id}") public ApiResponse<Document> detail(@PathVariable UUID id, HttpServletRequest r) { return ok(r, history.find(actor.require(), id)); }
    /** CAS保存正文，失败不能覆盖本地编辑稿。 */
    @PatchMapping("/history/{id}") public ApiResponse<Document> edit(@PathVariable UUID id, @Valid @RequestBody Edit input, HttpServletRequest r) { return ok(r, history.edit(actor.require(), id, input.polishedText(), input.expectedRevision())); }
    /** 用户业务删除始终逻辑删除。 */
    @DeleteMapping("/history/{id}") public ApiResponse<Void> delete(@PathVariable UUID id, HttpServletRequest r) { history.delete(actor.require(), id); return ok(r, null); }
    private <T> ApiResponse<T> ok(HttpServletRequest r, T value) { return ApiResponse.success((String)r.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), value); }
}
