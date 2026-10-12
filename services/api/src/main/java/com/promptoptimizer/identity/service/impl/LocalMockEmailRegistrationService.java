package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.dto.EmailRegistrationCodeRequest;
import com.promptoptimizer.identity.dto.EmailRegistrationCodeView;
import com.promptoptimizer.identity.dto.EmailRegistrationRequest;
import com.promptoptimizer.identity.service.EmailRegistrationService;
import com.promptoptimizer.identity.service.RegistrationException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 无数据库 local-mock 的注册适配器：明确告知能力不可用，不伪造投递或创建临时账户。
 * 完整开发/SMTP 环境仍使用 EmailRegistrationServiceImpl，不依赖本适配器或修改部署配置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("local-mock")
public final class LocalMockEmailRegistrationService implements EmailRegistrationService {

    /** Mock 模式保留协议入口，但不触达验证码存储、SMTP 或身份数据库。 */
    @Override
    public EmailRegistrationCodeView requestCode(EmailRegistrationCodeRequest request, String remoteAddress) {
        throw unavailable();
    }

    /** 不以虚拟注册成功绕过固定测试身份与账户持久化规则。 */
    @Override
    public RegisteredEmail register(EmailRegistrationRequest request) {
        throw unavailable();
    }

    /** 使用既有服务不可用错误码，避免缺失路由经错误分发被误报为认证失败。 */
    private RegistrationException unavailable() {
        return new RegistrationException(RegistrationException.Reason.SERVICE_UNAVAILABLE,
                "当前为本地 Mock 模式，不支持新账号注册；请切换完整开发环境。");
    }
}
