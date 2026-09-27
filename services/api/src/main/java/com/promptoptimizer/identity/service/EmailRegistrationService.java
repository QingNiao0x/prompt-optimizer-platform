package com.promptoptimizer.identity.service;

import com.promptoptimizer.identity.dto.EmailRegistrationCodeRequest;
import com.promptoptimizer.identity.dto.EmailRegistrationCodeView;
import com.promptoptimizer.identity.dto.EmailRegistrationRequest;

/**
 * 邮箱验证码注册的应用服务边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface EmailRegistrationService {

    /** 校验注册资格并按邮箱、来源地址限流后发送验证码。 */
    EmailRegistrationCodeView requestCode(EmailRegistrationCodeRequest request, String remoteAddress);

    /** 验证一次性代码后创建账户与默认工作区，成功后消费验证码。 */
    RegisteredEmail register(EmailRegistrationRequest request);

    /**
     * 注册成功后用于建立会话的规范化邮箱，不包含明文密码或验证码。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    record RegisteredEmail(String email) {
    }
}
