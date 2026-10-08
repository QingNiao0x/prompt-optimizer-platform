package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.service.LoginCaptchaService;
import com.promptoptimizer.identity.service.LoginGuardException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 图形验证码五分钟过期；共享 Redis 原子消费，显式本地模式使用原子内存存储。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class LoginCaptchaServiceImpl implements LoginCaptchaService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 4;

    private final SecureRandom random = new SecureRandom();
    private static final String CHALLENGE = ATTRIBUTE + "_ID";
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]); end; return v", String.class);
    private final StringRedisTemplate redis;
    private final boolean requireRedis;
    private final Clock clock;
    private final ConcurrentHashMap<String, LocalAnswer> local = new ConcurrentHashMap<>();

    /** 应用运行时继承现有登录防护的存储策略；短信配置另外强制要求共享 Redis。 */
    @Autowired
    public LoginCaptchaServiceImpl(ObjectProvider<StringRedisTemplate> redis, @Value("${app.security.login-guard.require-redis:true}") boolean requireRedis) {
        this(redis.getIfAvailable(), requireRedis, Clock.systemUTC());
    }

    /** 独立单元测试构造；生产由显式注入构造器决定存储策略。 */
    public LoginCaptchaServiceImpl() { this(null, false, Clock.systemUTC()); }

    LoginCaptchaServiceImpl(StringRedisTemplate redis, boolean requireRedis, Clock clock) {
        this.redis = redis; this.requireRedis = requireRedis; this.clock = clock;
    }

    /** 生成新验证码并替换会话中的旧答案，返回 PNG 图片。 */
    public byte[] issue(HttpServletRequest request) {
        String code = randomCode();
        String id = UUID.randomUUID().toString();
        if (requireRedis) {
            try {
                if (redis == null) throw new IllegalStateException();
                redis.opsForValue().set("prompt-optimizer:captcha:" + id, code, Duration.ofMinutes(5));
            } catch (RuntimeException exception) { throw unavailable(); }
        } else {
            local.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
            if (local.size() >= 10_000) throw unavailable();
            local.put(id, new LocalAnswer(code, clock.millis() + 300_000));
        }
        var session = request.getSession(true);
        session.setAttribute(CHALLENGE, id);
        // 兼容既有内部验收代码读取答案；公开接口始终只有图片，不返回会话内容。
        session.setAttribute(ATTRIBUTE, code);
        return render(code);
    }

    /** 核对后立即作废，错误或过期都要求用户刷新图片。 */
    public void verifyAndConsume(HttpServletRequest request, String submitted) {
        HttpSession session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(CHALLENGE);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
            session.removeAttribute(CHALLENGE);
        }
        String answer = null;
        if (stored instanceof String id) {
            if (requireRedis) {
                try {
                    if (redis == null) throw new IllegalStateException();
                    answer = redis.execute(CONSUME, List.of("prompt-optimizer:captcha:" + id));
                } catch (RuntimeException exception) { throw unavailable(); }
            } else {
                // remove 是一次性竞争点，即使两个请求拿到同一 Session 快照也只有一个能消费。
                var value = local.remove(id);
                if (value != null && value.expiresAt() > clock.millis()) answer = value.code();
            }
        }
        String provided = submitted == null ? "" : submitted.trim();
        if (answer == null || !answer.equalsIgnoreCase(provided)) {
            throw new LoginGuardException(
                    "CAPTCHA_INVALID",
                    "图形验证码错误或已过期，请刷新后重试。",
                    0
            );
        }
    }

    private static LoginGuardException unavailable() {
        return new LoginGuardException("LOGIN_GUARD_UNAVAILABLE", "登录防护服务暂不可用，请稍后重试。", 0);
    }

    private record LocalAnswer(String code, long expiresAt) { }

    private String randomCode() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private byte[] render(String code) {
        int width = 160;
        int height = 48;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(0xE8, 0xF0, 0xFB));
        graphics.fillRect(0, 0, width, height);
        Color[] specks = {
                new Color(0x4D, 0x6B, 0xFE),
                new Color(0x6B, 0x85, 0xFF),
                new Color(0x60, 0xA5, 0xFA),
                new Color(0x9A, 0xB6, 0xE8)
        };
        for (int index = 0; index < 26; index++) {
            graphics.setColor(specks[random.nextInt(specks.length)]);
            int radius = 2 + random.nextInt(3);
            graphics.fillOval(random.nextInt(width - 2), random.nextInt(height - 2), radius, radius);
        }
        graphics.setStroke(new BasicStroke(1f));
        for (int index = 0; index < 3; index++) {
            graphics.setColor(new Color(0x8A, 0xA4, 0xD4, 170));
            graphics.drawLine(0, random.nextInt(height), width, random.nextInt(height));
        }
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        for (int index = 0; index < code.length(); index++) {
            AffineTransform previous = graphics.getTransform();
            int x = 14 + index * 36;
            int y = 34;
            graphics.rotate((random.nextDouble() - 0.5) * 0.45, x + 10, y - 10);
            graphics.setColor(index % 2 == 0 ? new Color(0x4D, 0x6B, 0xFE) : new Color(0x3A, 0x56, 0xE0));
            graphics.drawString(String.valueOf(code.charAt(index)), x, y);
            graphics.setTransform(previous);
        }
        graphics.dispose();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("图形验证码生成失败", exception);
        }
    }
}
