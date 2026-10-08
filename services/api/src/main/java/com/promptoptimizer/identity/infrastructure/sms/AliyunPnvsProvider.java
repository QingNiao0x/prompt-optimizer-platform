package com.promptoptimizer.identity.infrastructure.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.teautil.models.RuntimeOptions;
import com.promptoptimizer.identity.service.SmsException;
import com.promptoptimizer.identity.service.SmsVerificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * PNVS 升级版 SDK 适配器；不返回验证码，不打印 SDK 请求、响应或原始异常。
 * @author QingNiao
 * @since 0.1.0
 */
public class AliyunPnvsProvider implements SmsVerificationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AliyunPnvsProvider.class);
    private final Client client;
    private final SmsProperties properties;
    public AliyunPnvsProvider(Client client, SmsProperties properties) { this.client = client; this.properties = properties; }

    @Override
    public void send(String phone, String scheme, String template, String outId) {
        long started = System.nanoTime();
        String outcome = "PROVIDER_UNAVAILABLE";
        var request = new SendSmsVerifyCodeRequest().setPhoneNumber(phone.substring(3)).setCountryCode("86")
                .setSignName(properties.getSignName()).setTemplateCode(template).setSchemeName(scheme)
                .setTemplateParam("{\"code\":\"##code##\",\"min\":\"5\"}")
                .setCodeLength(6L).setCodeType(1L).setValidTime(300L).setInterval(60L)
                .setDuplicatePolicy(1L).setReturnVerifyCode(false).setAutoRetry(0L).setOutId(outId);
        try {
            var response = client.sendSmsVerifyCodeWithOptions(request, options());
            if (response == null || response.getBody() == null) throw SmsException.unavailable();
            var body = response.getBody();
            if (!Boolean.TRUE.equals(body.getSuccess()) || !"OK".equals(body.getCode())) throw rejected(body.getCode());
            outcome = "ACCEPTED";
        } catch (SmsException exception) { outcome = exception.getCode(); throw exception;
        } catch (Exception exception) {
            // TeaException 的 message/data 可能带请求参数，绝不能把 exception 作为日志参数。
            throw SmsException.unavailable();
        } finally {
            diagnostic("send", scheme, outcome, started);
        }
    }

    @Override
    public boolean verify(String phone, String scheme, String code, String outId) {
        long started = System.nanoTime();
        String outcome = "PROVIDER_UNAVAILABLE";
        var request = new CheckSmsVerifyCodeRequest().setPhoneNumber(phone.substring(3)).setCountryCode("86")
                .setSchemeName(scheme).setVerifyCode(code).setOutId(outId);
        try {
            var response = client.checkSmsVerifyCodeWithOptions(request, options());
            if (response == null || response.getBody() == null) throw SmsException.unavailable();
            var body = response.getBody();
            if (!Boolean.TRUE.equals(body.getSuccess()) || !"OK".equals(body.getCode())) throw rejected(body.getCode());
            boolean passed = body.getModel() != null && "PASS".equals(body.getModel().getVerifyResult());
            outcome = passed ? "PASS" : "NOT_PASS";
            return passed;
        } catch (SmsException exception) { outcome = exception.getCode(); throw exception;
        } catch (Exception exception) {
            throw SmsException.unavailable();
        } finally {
            diagnostic("verify", scheme, outcome, started);
        }
    }

    /** 仅记录本地白名单结果与用途，不记录部署方案名、标识、验证码或云端正文。 */
    private void diagnostic(String operation, String scheme, String outcome, long started) {
        String purpose = scheme.endsWith("-register") ? "REGISTER"
                : scheme.endsWith("-login") ? "LOGIN" : scheme.endsWith("-bind") ? "BIND" : "UNKNOWN";
        LOG.info("event=sms.provider.completed requestId={} operation={} purpose={} outcome={} durationMs={}",
                MDC.get("requestId"), operation, purpose, outcome, (System.nanoTime() - started) / 1_000_000);
    }

    /** 网络结果不确定时不得自动重发；超时小于挑战有效期且不忽略TLS证书。 */
    private RuntimeOptions options() {
        return new RuntimeOptions().setAutoretry(false).setConnectTimeout(3000).setReadTimeout(8000);
    }

    /** 仅映射明确白名单错误，其余云错误统一处理，不透传原文。 */
    private SmsException rejected(String code) {
        if ("FREQUENCY_FAIL".equals(code) || "BUSINESS_LIMIT_CONTROL".equals(code)) {
            return new SmsException(429, "SMS_PROVIDER_RATE_LIMITED", "短信请求过于频繁，请稍后重试。", 60);
        }
        return SmsException.unavailable();
    }
}
