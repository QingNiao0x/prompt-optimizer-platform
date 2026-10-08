package com.promptoptimizer.identity.infrastructure.registration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * 后端自行渲染的纯文本验证码邮件，不依赖云控制台的模板编号。
 * 当前只有注册流程使用本枚举；登录和绑定文案仅预留，不代表相应接口已开放。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum VerificationEmailTemplate {

    REGISTRATION("【PromptOptimizer】邮箱注册验证码", """
            您好！

            您正在使用此邮箱注册 PromptOptimizer-提示词优化工具 账号。

            您的邮箱验证码为：

            {Code}

            验证码在 {Minutes} 分钟内有效，仅用于本次注册。

            为了保障账户安全，请勿向任何人透露此验证码，也不要通过回复邮件提交验证码。

            如果这不是您本人发起的操作，请忽略此邮件。

            感谢您使用 PromptOptimizer。

            这是一封系统自动发送的邮件，请勿直接回复。
            """),

    /** 预留文案；当前系统未开放邮箱验证码登录。 */
    LOGIN("【PromptOptimizer】｜登录验证码", """
            您好！

            您正在登录 Prompt Optimizer-提示词优化工具，请使用以下验证码完成邮箱验证：

            {Code}

            验证码在 {Minutes} 分钟内有效，仅用于本次登录验证。

            请勿向任何人透露验证码，Prompt Optimizer 不会通过电话或聊天向您索取验证码。

            如果这不是您本人发起的操作，请忽略此邮件。

            Prompt Optimizer

            这是一封系统自动发送的邮件，请勿直接回复。
            """),

    /** 预留文案；首次绑定邮箱能力需后续单独实现与授权。 */
    EMAIL_BINDING("【PromptOptimizer】｜邮箱绑定验证", """
            您好！

            您正在将此邮箱绑定到 Prompt Optimizer-提示词优化工具 账号，请使用以下验证码完成验证：

            {Code}

            验证码在 {Minutes} 分钟内有效，仅用于本次邮箱绑定验证。

            绑定后，此邮箱将用于账户身份验证及相关安全通知。请确认这是您本人发起的操作，并妥善保管验证码。

            如果您没有申请绑定此邮箱，请忽略此邮件，不要将验证码提供给他人。

            Prompt Optimizer

            这是一封系统自动发送的邮件，请勿直接回复。
            """);

    private final String subject;
    private final String bodyTemplate;

    VerificationEmailTemplate(String subject, String bodyTemplate) {
        this.subject = subject;
        this.bodyTemplate = bodyTemplate;
    }

    /**
     * 替换服务端生成的六位数字验证码与实际有效时长；不接收客户端自定义正文。
     * 返回结果包含一次性验证码，调用方不得将其记录到日志或接口响应中。
     */
    public EmailContent render(String code, Duration validFor) {
        if (code == null || !code.matches("[0-9]{6}") || validFor == null || validFor.toSeconds() < 1) {
            throw new IllegalArgumentException("验证码邮件需要六位数字验证码和有效时长。");
        }
        // 配置允许以秒表示 TTL，按实际秒数换算，避免把不足一分钟的验证码说成有效零分钟。
        String minutes = BigDecimal.valueOf(validFor.toSeconds())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
        return new EmailContent(subject, bodyTemplate.replace("{Code}", code).replace("{Minutes}", minutes));
    }

    /**
     * 单次投递内容；正文含敏感验证码，禁止使用自动生成的对象字符串输出内容。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record EmailContent(String subject, String body) {
        /** 避免调试日志无意输出正文中的验证码。 */
        @Override
        public String toString() {
            return "EmailContent[redacted]";
        }
    }
}
