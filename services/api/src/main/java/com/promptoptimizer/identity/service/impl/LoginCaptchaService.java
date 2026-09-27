package com.promptoptimizer.identity.service.impl;

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

/**
 * 签发一次性图形验证码，答案只放在服务端会话中。
 */
@Service
public class LoginCaptchaService {

    public static final String ATTRIBUTE = "LOGIN_CAPTCHA";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    /** 生成新验证码并替换会话中的旧答案，返回 PNG 图片。 */
    public byte[] issue(HttpServletRequest request) {
        String code = randomCode();
        request.getSession(true).setAttribute(ATTRIBUTE, code);
        return render(code);
    }

    /** 核对后立即作废，错误或过期都要求用户刷新图片。 */
    public void verifyAndConsume(HttpServletRequest request, String submitted) {
        HttpSession session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(ATTRIBUTE);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
        }
        String answer = stored instanceof String value ? value : "";
        String provided = submitted == null ? "" : submitted.trim();
        if (answer.isEmpty() || !answer.equalsIgnoreCase(provided)) {
            throw new LoginGuardException(
                    "CAPTCHA_INVALID",
                    "图形验证码错误或已过期，请刷新后重试。",
                    0
            );
        }
    }

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
