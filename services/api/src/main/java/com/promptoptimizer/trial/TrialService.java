package com.promptoptimizer.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import com.promptoptimizer.common.logging.LogCorrelation;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 服务器签发访客凭证并集中处理三类总额度；不注册用户、不赋予角色、不改变正式登录身份。
 * 生成前原子扣一次尝试，重放只恢复原件，未知结果不退款以防重试套利。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class TrialService {
    public static final String COOKIE = "PO_TRIAL";
    private final TrialStore store;
    private final ObjectMapper json;
    private final ClientIpResolver ips;
    private final boolean enabled;
    private final boolean secure;
    private final SecureRandom random = new SecureRandom();

    public TrialService(TrialStore store, ObjectMapper json, ClientIpResolver ips,
            @Value("${app.trial.enabled:true}") boolean enabled,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.store = store; this.json = json; this.ips = ips; this.enabled = enabled; this.secure = secure;
    }

    /** 查询不会创建访客；正式登录身份只报告认证状态，不将其转换为访客。 */
    public Status status(HttpServletRequest request) {
        String guest = enabled ? existing(request) : null;
        var quotas = new LinkedHashMap<String, Quota>();
        for (var feature : TrialFeature.values()) {
            int used = guest == null ? 0 : store.used(guest, feature);
            quotas.put(feature.key(), new Quota(feature.limit(), used, Math.max(0, feature.limit() - used)));
        }
        return new Status(authenticated(), enabled, guest != null, Map.copyOf(quotas));
    }

    /** 显式开始体验才签发一年有效的HttpOnly凭证，额度账本不随会话重启或自然日重置。 */
    public Status start(HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        if (authenticated()) return status(request);
        if (existing(request) == null) {
            byte[] token = new byte[32]; random.nextBytes(token);
            String credential = HexFormat.of().formatHex(token);
            store.create(hash(credential), source(request));
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, credential)
                    .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(Duration.ofDays(365)).build().toString());
            // 当前响应即可显示真实初始额度；随后请求通过Cookie绑定同一访客。
            var quotas = new LinkedHashMap<String, Quota>();
            for (var feature : TrialFeature.values()) quotas.put(feature.key(), new Quota(feature.limit(), 0, feature.limit()));
            return new Status(false, true, true, Map.copyOf(quotas));
        }
        return status(request);
    }

    /** 访客标识只来自服务端签发凭证和Redis账本，不接受请求正文中的用户编号。 */
    public String guest(HttpServletRequest request) {
        requireEnabled();
        if (authenticated()) throw new TrialException(409, "TRIAL_REGISTERED_USER", "已登录，请使用账号功能入口。");
        String guest = existing(request);
        if (guest == null) throw new TrialException(401, "TRIAL_SESSION_REQUIRED", "请先开启免费体验。");
        return guest;
    }

    /** 上传不计生成次数，但已耗尽图像额度的访客不能继续占用图片存储。 */
    public void requireRemaining(String guest, TrialFeature feature) {
        if (store.used(guest, feature) >= feature.limit())
            throw new TrialException(429, "TRIAL_QUOTA_EXHAUSTED", "该功能的免费体验次数已用完，请注册登录后继续。");
    }

    /** 访客最多尝试上传五次，额度用尽后不能继续占用临时图片资源。 */
    public void claimUpload(String guest) {
        requireRemaining(guest, TrialFeature.IMAGE_PROMPT);
        store.claimUpload(guest);
    }

    /** 验证输入后调用此方法：相同操作与内容至多执行一次，已消费的旧操作永不重新放行。 */
    public <T> T execute(HttpServletRequest request, TrialFeature feature, UUID operation, Object input,
                         Class<T> resultType, Supplier<T> action) {
        String guest = guest(request);
        String fingerprint;
        try { fingerprint = hash(json.writeValueAsString(input)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw TrialException.unavailable(failure); }
        if (!store.reserve(guest, source(request), feature, operation, fingerprint)) {
            TrialStore.Snapshot existing = snapshot(guest, feature, operation);
            if (!existing.fingerprint().equals(fingerprint)) throw new TrialException(409, "TRIAL_OPERATION_CONFLICT", "同一操作编号不能用于不同输入。");
            if (!existing.status().equals("COMPLETED")) throw new TrialException(409, "TRIAL_OPERATION_INCOMPLETE", "该次体验尚未完成或已失败，请查询结果后明确发起新操作。");
            return decode(existing, resultType);
        }
        Instant started = Instant.now();
        try (var correlation = LogCorrelation.bindWorkflow("trial-" + feature.key(), operation.toString())) {
            T result = action.get();
            String encoded = json.writeValueAsString(result);
            // 结果已生成后，快照保存失败不能误报成模型失败；次数已原子消费，不允许再次重放。
            try { store.finish(guest, feature, operation, new TrialStore.Snapshot("COMPLETED", fingerprint, encoded, started, started.plusSeconds(86400))); }
            catch (TrialException failure) { LoggerFactory.getLogger(getClass()).warn("event=trial.result_store_failed feature={}", feature); }
            return result;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            try { store.finish(guest, feature, operation, new TrialStore.Snapshot("FAILED", fingerprint, null, started, started.plusSeconds(86400))); }
            catch (TrialException ignored) { /* 保持已消费状态，禁止通过存储异常退回次数。 */ }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw TrialException.unavailable(failure);
        }
    }

    /** 断网恢复仅读取当前访客的短期结果，不提供任何历史列表或跨账号保存入口。 */
    public <T> Operation<T> operation(HttpServletRequest request, TrialFeature feature, UUID id, Class<T> type) {
        var state = snapshot(guest(request), feature, id);
        String status = state.status().equals("RUNNING") && state.startedAt().plusSeconds(600).isBefore(Instant.now()) ? "UNKNOWN" : state.status();
        return new Operation<>(status, state.resultJson() == null ? null : decode(state, type), state.startedAt());
    }
    /** 原件恢复必须同时匹配访客、功能和操作；不存在统一返回过期，不推断其他用户资源。 */
    private TrialStore.Snapshot snapshot(String guest, TrialFeature feature, UUID id) {
        var state = store.get(guest, feature, id);
        if (state == null) throw new TrialException(404, "TRIAL_OPERATION_EXPIRED", "本次体验结果不存在或已过期，请注册登录后保存后续结果。");
        return state;
    }
    /** 只解析服务器保存的短期结果，损坏结果不能触发模型重放或回退到客户端正文。 */
    private <T> T decode(TrialStore.Snapshot snapshot, Class<T> type) {
        try { return json.readValue(snapshot.resultJson(), type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw TrialException.unavailable(failure); }
    }
    /** Cookie只是随机凭据，格式正确仍必须存在服务器账本；不能据旧Cookie恢复已丢失身份。 */
    private String existing(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (COOKIE.equals(cookie.getName()) && cookie.getValue().matches("[a-f0-9]{64}")) {
            String guest = hash(cookie.getValue()); return store.exists(guest) ? guest : null;
        }
        return null;
    }
    /** 信任代理规则复用现有配置，HMAC避免在计数键中保存原始IP。 */
    private String source(HttpServletRequest request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(store.salt().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(Objects.toString(ips.resolve(request), "unknown").getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw TrialException.unavailable(failure); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private void requireEnabled() { if (!enabled) throw new TrialException(503, "TRIAL_DISABLED", "免费体验暂未开放，请注册登录。"); }
    private boolean authenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }
    public record Quota(int limit, int used, int remaining) { }
    public record Status(boolean authenticated, boolean available, boolean initialized, Map<String, Quota> quotas) { }
    /** 与工具原恢复协议兼容的最小私有视图，不暴露来源IP或输入指纹。 */
    public record Operation<T>(String status, T document, Instant startedAt) { }
}
