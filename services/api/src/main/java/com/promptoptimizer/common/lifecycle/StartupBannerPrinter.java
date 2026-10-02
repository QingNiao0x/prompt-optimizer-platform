package com.promptoptimizer.common.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后打印提示词优化平台的 ASCII 横幅与运行信息，不参与业务初始化。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class StartupBannerPrinter {

    private static final Logger log = LoggerFactory.getLogger(StartupBannerPrinter.class);

    private static final int MAX_LINE_WIDTH = 100;
    private static final String GITHUB_USERNAME = "QingNiao0x";

    /**
     * 对话气泡经过整理，形成包含背景、任务、输出和约束的结构化提示词。
     *
     * <p>这里使用纯 ASCII 字符，避免不同终端对宽字符渲染不一致。</p>
     */
    private static final String[] PROMPT_ART = {
            ".--------------------.            /\\            .--------------------------.",
            "| > an idea ...      |           /  \\           | CONTEXT     [========]   |",
            "| ? a question       |    ----> < {} > ---->    | TASK        [========]   |",
            "| ...                |           \\  /           | OUTPUT      [========]   |",
            "'----.  .------------'            \\/            | CONSTRAINTS [========]   |",
            "     \\/                                         '--------------------------'",
            "",
            "      RAW PROMPT               OPTIMIZE              STRUCTURED PROMPT",
            "",
            "                         PROMPT OPTIMIZER PLATFORM",
            "                    Turn intent into structured prompts.",
    };

    private final Environment environment;

    /**
     * 使用 Spring 环境读取当前 Profile 与服务端口。
     */
    public StartupBannerPrinter(Environment environment) {
        this.environment = environment;
    }

    /**
     * 应用就绪事件触发时打印启动图案。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String profile = String.join(",", environment.getActiveProfiles());
        String port = environment.getProperty("server.port", "8080");
        String apiBase = "http://localhost:" + port + "/api/v1";
        log.info("\n{}", buildBanner(profile, apiBase));
    }

    /**
     * 按项目图案、顶格 GitHub 署名、运行信息的顺序组装横幅；未指定 Profile 时使用 default。
     */
    String buildBanner(String profile, String apiBase) {
        if (profile == null || profile.isBlank()) {
            profile = "default";
        }

        return String.join("\n", PROMPT_ART) + "\n\n"
                + GITHUB_USERNAME + "\n"
                + formatInfo("Profile : ", profile) + "\n"
                + formatInfo("API     : ", apiBase);
    }

    /**
     * 超长运行信息主动折行并对齐到值的起始列，保留完整内容且每行不超过 100 个字符。
     */
    private static String formatInfo(String prefix, String value) {
        int valueWidth = MAX_LINE_WIDTH - prefix.length();
        StringBuilder result = new StringBuilder(prefix);
        for (int start = 0; start < value.length(); start += valueWidth) {
            if (start > 0) {
                // 续行不重复标签，避免多 Profile 被误读成多个独立信息项。
                result.append('\n').append(" ".repeat(prefix.length()));
            }
            result.append(value, start, Math.min(start + valueWidth, value.length()));
        }
        return result.toString();
    }
}
