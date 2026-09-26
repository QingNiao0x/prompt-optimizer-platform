package com.promptoptimizer.identity.service;

import com.promptoptimizer.identity.dto.EmailRegistrationCodeRequest;
import com.promptoptimizer.identity.dto.EmailRegistrationCodeView;
import com.promptoptimizer.identity.dto.EmailRegistrationRequest;
import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.infrastructure.registration.RegistrationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * 邮箱验证码申请与注册编排。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class EmailRegistrationService {

    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    private final UserIdentityMapper identityMapper;
    private final EmailVerificationStore verificationStore;
    private final VerificationEmailSender emailSender;
    private final RegistrationProperties properties;
    private final AccountRegistrationGateway registrationGateway;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;

    public EmailRegistrationService(
            UserIdentityMapper identityMapper,
            EmailVerificationStore verificationStore,
            VerificationEmailSender emailSender,
            RegistrationProperties properties,
            AccountRegistrationGateway registrationGateway,
            PasswordEncoder passwordEncoder
    ) {
        this.identityMapper = identityMapper;
        this.verificationStore = verificationStore;
        this.emailSender = emailSender;
        this.properties = properties;
        this.registrationGateway = registrationGateway;
        this.passwordEncoder = passwordEncoder;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 校验注册资格并按邮箱、来源地址限流后发送验证码；邮件投递失败会撤销本次发码状态。
     */
    public EmailRegistrationCodeView requestCode(EmailRegistrationCodeRequest request, String remoteAddress) {
        ensureAvailable();
        UserIdentityKey emailKey = UserIdentityKey.email(request.email());
        ensureEmailNotRegistered(emailKey);

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        String emailFingerprint = fingerprint(emailKey.normalizedIdentifier());
        String ipFingerprint = fingerprint(remoteAddress == null ? "unknown" : remoteAddress.trim());
        String codeDigest = codeDigest(emailKey.normalizedIdentifier(), code);
        EmailVerificationPolicy policy = properties.policy();

        EmailVerificationStore.IssueDecision decision = verificationStore.issue(
                emailFingerprint,
                ipFingerprint,
                codeDigest,
                policy
        );
        enforceIssueDecision(decision);

        try {
            emailSender.send(emailKey.normalizedIdentifier(), code, policy.codeTtl());
        } catch (RuntimeException exception) {
            verificationStore.cancelIssue(emailFingerprint, ipFingerprint, codeDigest);
            if (exception instanceof RegistrationException registrationException) {
                throw registrationException;
            }
            throw new RegistrationException(
                    RegistrationException.Reason.DELIVERY_UNAVAILABLE,
                    "验证码邮件暂时无法发送，请稍后重试。"
            );
        }

        return new EmailRegistrationCodeView(
                policy.resendInterval().toSeconds(),
                policy.codeTtl().toSeconds()
        );
    }

    /** 验证一次性代码后创建账户与默认工作区，成功后消费验证码。 */
    public RegisteredEmail register(EmailRegistrationRequest request) {
        ensureAvailable();
        UserIdentityKey emailKey = UserIdentityKey.email(request.email());
        validatePassword(request.password());

        String emailFingerprint = fingerprint(emailKey.normalizedIdentifier());
        String codeDigest = codeDigest(emailKey.normalizedIdentifier(), request.verificationCode());
        EmailVerificationStore.VerificationResult result = verificationStore.verify(emailFingerprint, codeDigest);
        enforceVerificationResult(result);

        ensureEmailNotRegistered(emailKey);
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            registrationGateway.createPersonalAccount(
                    emailKey.normalizedIdentifier(),
                    deriveDisplayName(emailKey.normalizedIdentifier()),
                    passwordHash,
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
        } catch (DataIntegrityViolationException exception) {
            throw alreadyRegistered();
        }
        verificationStore.consume(emailFingerprint, codeDigest);
        return new RegisteredEmail(emailKey.normalizedIdentifier());
    }

    private void ensureAvailable() {
        byte[] secretBytes = properties.getVerificationSecret().getBytes(StandardCharsets.UTF_8);
        if (!properties.isEnabled() || secretBytes.length < 32) {
            throw new RegistrationException(
                    RegistrationException.Reason.SERVICE_UNAVAILABLE,
                    "邮箱注册暂时不可用，请联系管理员。"
            );
        }
    }

    private void ensureEmailNotRegistered(UserIdentityKey emailKey) {
        boolean registered = identityMapper.existsByLoginKey(
                emailKey.type(), emailKey.issuer(), emailKey.normalizedIdentifier());
        if (registered) {
            throw alreadyRegistered();
        }
    }

    private RegistrationException alreadyRegistered() {
        return new RegistrationException(
                RegistrationException.Reason.EMAIL_ALREADY_REGISTERED,
                "该邮箱已注册，请直接登录。"
        );
    }

    /** 注册密码必须同时包含字母和数字，并限制在 BCrypt 可处理的长度内。 */
    private void validatePassword(String password) {
        if (!PasswordPolicy.meets(password)) {
            throw new RegistrationException(
                    RegistrationException.Reason.PASSWORD_INVALID,
                    PasswordPolicy.rejectionMessage()
            );
        }
    }

    /** 将发码限流结果转成可读错误，并保留建议重试等待时间。 */
    private void enforceIssueDecision(EmailVerificationStore.IssueDecision decision) {
        switch (decision.result()) {
            case ISSUED -> {
                return;
            }
            case RESEND_TOO_SOON -> throw new RegistrationException(
                    RegistrationException.Reason.RESEND_TOO_SOON,
                    "验证码发送过于频繁，请稍后重试。",
                    decision.retryAfterSeconds()
            );
            case EMAIL_RATE_LIMITED -> throw new RegistrationException(
                    RegistrationException.Reason.EMAIL_RATE_LIMITED,
                    "该邮箱验证码请求次数过多，请稍后重试。",
                    decision.retryAfterSeconds()
            );
            case IP_RATE_LIMITED -> throw new RegistrationException(
                    RegistrationException.Reason.IP_RATE_LIMITED,
                    "当前网络验证码请求次数过多，请稍后重试。",
                    decision.retryAfterSeconds()
            );
        }
    }

    /** 区分无效、过期和尝试次数耗尽的验证码，避免注册流程继续执行。 */
    private void enforceVerificationResult(EmailVerificationStore.VerificationResult result) {
        switch (result) {
            case VALID -> {
                return;
            }
            case INVALID -> throw new RegistrationException(
                    RegistrationException.Reason.CODE_INVALID,
                    "验证码错误。"
            );
            case EXPIRED -> throw new RegistrationException(
                    RegistrationException.Reason.CODE_EXPIRED,
                    "验证码已过期，请重新获取。"
            );
            case ATTEMPTS_EXHAUSTED -> throw new RegistrationException(
                    RegistrationException.Reason.CODE_ATTEMPTS_EXHAUSTED,
                    "验证码尝试次数已用尽，请重新获取。"
            );
        }
    }

    /** 以服务端密钥对邮箱与验证码计算 HMAC，存储层无需保存明文验证码。 */
    private String codeDigest(String normalizedEmail, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getVerificationSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            return HexFormat.of().formatHex(mac.doFinal(
                    (normalizedEmail + "\n" + code).getBytes(StandardCharsets.UTF_8)
            ));
        } catch (GeneralSecurityException exception) {
            throw new RegistrationException(
                    RegistrationException.Reason.SERVICE_UNAVAILABLE,
                    "验证码服务暂时不可用，请稍后重试。"
            );
        }
    }

    /** 把邮箱或来源地址转为固定长度摘要，避免它们直接出现在限流键中。 */
    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new RegistrationException(
                    RegistrationException.Reason.SERVICE_UNAVAILABLE,
                    "验证码服务暂时不可用，请稍后重试。"
            );
        }
    }

    private String deriveDisplayName(String normalizedEmail) {
        String localPart = normalizedEmail.substring(0, normalizedEmail.indexOf('@')).trim();
        if (localPart.isBlank()) {
            return "新用户";
        }
        return localPart.length() <= 80 ? localPart : localPart.substring(0, 80);
    }

    /**
     * 注册成功后用于建立会话的规范化邮箱，不包含明文密码或验证码。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record RegisteredEmail(String email) {
    }
}
