package com.promptoptimizer.identity.settings;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.domain.UserDisplayNames;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 普通登录用户的系统设置；所有写入继承现有CSRF校验，目标身份只来自服务端会话。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/me/settings")
public class AccountSettingsController {
    private final ObjectProvider<AccountSettingsService> services;
    private final CurrentActor currentActor;
    private final CsrfTokenRepository csrf;
    private final SecurityContextRepository contexts;

    public AccountSettingsController(ObjectProvider<AccountSettingsService> services, CurrentActor currentActor,
            CsrfTokenRepository csrf, SecurityContextRepository contexts) {
        this.services = services;
        this.currentActor = currentActor;
        this.csrf = csrf;
        this.contexts = contexts;
    }

    /** Mock模式可读取模拟资料，但不会把模拟主体写入真实账户表。 */
    @GetMapping
    public ApiResponse<AccountSettingsView> read(HttpServletRequest request) {
        var service = services.getIfAvailable();
        if (service != null) return success(request, service.read());
        var actor = currentActor.require();
        return success(request, new AccountSettingsView(actor.displayName(), actor.email().isBlank() ? null : actor.email(),
                null, "zh-CN", false, false));
    }

    /** 修改昵称并更新当前Session展示快照，其他设备下次读取仍以数据库为准。 */
    @PatchMapping("/profile")
    public ApiResponse<AccountSettingsView> profile(@Valid @RequestBody Profile body,
            HttpServletRequest request, HttpServletResponse response) {
        var view = service().profile(body.displayName());
        refreshProfile(view, request, response);
        return success(request, view);
    }

    /** 界面语言独立持久化，不触发登出。 */
    @PatchMapping("/preferences")
    public ApiResponse<AccountSettingsView> preferences(@Valid @RequestBody Preferences body, HttpServletRequest request) {
        return success(request, service().preferences(body.locale()));
    }

    /** 当前密码确认后向目标邮箱发送独立绑定验证码。 */
    @PostMapping("/email-challenges")
    public ApiResponse<AccountSettingsService.ChallengeView> challenge(@Valid @RequestBody EmailChallenge body, HttpServletRequest request) {
        return success(request, service().challenge(body.email(), body.currentPassword(), request));
    }

    /** 邮箱变更提交后清除当前会话；其他设备通过安全版本立即失效。 */
    @PostMapping("/email")
    public ApiResponse<AccountSettingsView> email(@Valid @RequestBody EmailBinding body,
            HttpServletRequest request, HttpServletResponse response) {
        var view = service().email(body.email(), body.challengeId(), body.verificationCode(), body.currentPassword(), request);
        logout(request, response);
        return success(request, view);
    }

    /** 用户名变更同样要求重新登录，旧用户名身份保留为已撤销。 */
    @PatchMapping("/username")
    public ApiResponse<AccountSettingsView> username(@Valid @RequestBody Username body,
            HttpServletRequest request, HttpServletResponse response) {
        var view = service().username(body.username(), body.currentPassword(), request);
        logout(request, response);
        return success(request, view);
    }

    /** 数据库版本成功更新后，才清理本设备Session与CSRF Cookie。 */
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(@Valid @RequestBody Password body, HttpServletRequest request, HttpServletResponse response) {
        service().logoutAll(body.currentPassword(), request);
        logout(request, response);
        return success(request, null);
    }

    /** 更换密码提交后退出当前设备，其他设备的旧会话在后续请求时失效。 */
    @PatchMapping("/password")
    public ApiResponse<Void> password(@Valid @RequestBody ChangePassword body, HttpServletRequest request, HttpServletResponse response) {
        service().changePassword(body.currentPassword(), body.newPassword(), request);
        logout(request, response);
        return success(request, null);
    }

    /** 二次确认逻辑注销；保留历史与租户，不宣称完成物理数据删除。 */
    @PostMapping("/deactivate")
    public ApiResponse<Void> deactivate(@Valid @RequestBody Deactivate body, HttpServletRequest request, HttpServletResponse response) {
        service().deactivate(body.currentPassword(), body.confirmation(), request);
        logout(request, response);
        return success(request, null);
    }

    private AccountSettingsService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new AccountSettingsException(503, "ACCOUNT_SETTINGS_UNAVAILABLE", "当前模拟环境不支持修改真实账户设置。");
        return service;
    }

    /** 响应生成前清除当前认证，但不在数据库提交前提前登出。 */
    private void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        csrf.saveToken(null, request, response);
    }

    /** 昵称变更不轮换认证版本，保存无密码的新展示快照即可。 */
    private void refreshProfile(AccountSettingsView view, HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            var actor = principal.actorIdentity();
            var fresh = new AuthenticatedUser(actor.userId(), actor.tenantId(), actor.workspaceId(), actor.email(),
                    principal.getUsername(), view.displayName(), "", "ACTIVE", principal.getAuthorities(),
                    principal.getIdentityId(), principal.getSecurityVersion());
            fresh.eraseCredentials();
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
            contexts.saveContext(SecurityContextHolder.getContext(), request, response);
        }
    }

    private static <T> ApiResponse<T> success(HttpServletRequest request, T value) {
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), value);
    }

    /** 昵称仅作显示资料，沿用既有单行和80字符规则。 */
    public record Profile(@NotBlank @Size(max = 80) @Pattern(regexp = UserDisplayNames.SINGLE_LINE_PATTERN) String displayName) { }
    /** 账户界面语言白名单，避免写入未支持语言。 */
    public record Preferences(@NotBlank @Pattern(regexp = "zh-CN|en") String locale) { }
    /** 密码只用于本次重新认证；禁止自动生成对象字符串泄露。 */
    public record Password(@NotBlank @Size(max = 256) String currentPassword) {
        @Override public String toString() { return "Password[redacted]"; }
    }
    /** 当前密码和新密码仅在本次请求中用于验证与哈希，禁止默认字符串表示泄漏。 */
    public record ChangePassword(@NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(max = 200) String newPassword) {
        @Override public String toString() { return "ChangePassword[redacted]"; }
    }
    /** 发码请求不允许客户端选择目标账户。 */
    public record EmailChallenge(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 256) String currentPassword) {
        @Override public String toString() { return "EmailChallenge[redacted]"; }
    }
    /** 验证码绑定目标邮箱、挑战及当前会话，原始秘密不得出现在日志。 */
    public record EmailBinding(@NotBlank @Email @Size(max = 254) String email, @NotNull UUID challengeId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String verificationCode, @NotBlank @Size(max = 256) String currentPassword) {
        @Override public String toString() { return "EmailBinding[redacted]"; }
    }
    /** 用户名在应用层复用UserIdentityKey进行大小写与格式规范化。 */
    public record Username(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String currentPassword) {
        @Override public String toString() { return "Username[redacted]"; }
    }
    /** 显式确认词用于二次确认，不接受未经确认的账号注销。 */
    public record Deactivate(@NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Pattern(regexp = "DELETE") String confirmation) {
        @Override public String toString() { return "Deactivate[redacted]"; }
    }
}
