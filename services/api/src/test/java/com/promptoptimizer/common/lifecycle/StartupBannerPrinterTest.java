package com.promptoptimizer.common.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证提示词优化平台启动横幅的业务图案、终端排版与就绪日志，不启动 Spring 容器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class StartupBannerPrinterTest {

    @Test
    void buildBannerShouldContainArtworkProfileAndApiBase() {
        StartupBannerPrinter printer = new StartupBannerPrinter(new MockEnvironment());

        String banner = printer.buildBanner("default", "http://localhost:18080/api/v1");
        List<String> lines = banner.lines().toList();
        int usernameLine = lines.indexOf("QingNiao0x");

        assertTrue(usernameLine > 0, "用户名应独占一行并顶格显示在图案之后");
        assertEquals(1L, lines.stream().filter(line -> line.contains("QingNiao0x")).count(),
                "用户名只应出现一次");
        assertEquals(List.of("QingNiao0x", "Profile : default", "API     : http://localhost:18080/api/v1"),
                lines.subList(usernameLine, lines.size()), "运行信息应紧随用户名且没有额外空行");
        assertEquals(lines.get(usernameLine + 1).indexOf(':'), lines.get(usernameLine + 2).indexOf(':'),
                "Profile 与 API 标签的冒号应对齐");

        String artwork = String.join("\n", lines.subList(0, usernameLine));
        for (String label : List.of("PROMPT OPTIMIZER PLATFORM", "RAW PROMPT", "OPTIMIZE", "STRUCTURED PROMPT",
                "CONTEXT", "TASK", "OUTPUT", "CONSTRAINTS")) {
            assertTrue(artwork.contains(label), "GitHub 署名之前应展示项目名称、提示词优化流程与四要素：" + label);
        }
        assertTrue(artwork.lines().anyMatch(line -> line.contains("RAW PROMPT")
                        && line.contains("OPTIMIZE")
                        && line.contains("STRUCTURED PROMPT")
                        && line.indexOf("RAW PROMPT") < line.indexOf("OPTIMIZE")
                        && line.indexOf("OPTIMIZE") < line.indexOf("STRUCTURED PROMPT")),
                "流程标签应位于同一行，从左向右表示原始提示词、优化、结构化提示词");
        assertTrue(artwork.chars().allMatch(character -> character == '\n' || character >= 32 && character <= 126),
                "图案应仅使用可打印 ASCII 和换行，避免终端宽字符错位");
        assertFalse(banner.contains("\t"), "排版应使用空格而非依赖终端制表宽度");
        assertFalse(banner.contains("\r"), "横幅内部应使用一致的换行符");
        assertTrue(lines.stream().allMatch(line -> line.equals(line.stripTrailing())), "每行不应包含尾随空格");
        assertTrue(lines.stream().allMatch(line -> line.length() <= 100), "横幅每行宽度不应超过 100");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", " \n\t "})
    void buildBannerShouldUseBlankProfileFallback(String profile) {
        StartupBannerPrinter printer = new StartupBannerPrinter(new MockEnvironment());

        String banner = printer.buildBanner(profile, "http://localhost:8080/api/v1");

        assertTrue(banner.contains("Profile : default"), "空 Profile 应回退为 default");
        assertEquals(1L, banner.lines().filter(line -> line.contains("Profile :")).count(), "Profile 只应出现一次");
    }

    @ParameterizedTest
    @CsvSource({"90, 1", "91, 2", "215, 3"})
    void buildBannerShouldWrapLongRuntimeInfoWithoutLosingContent(int valueLength, int expectedRows) {
        StartupBannerPrinter printer = new StartupBannerPrinter(new MockEnvironment());
        String profile = "profile-" + "p".repeat(valueLength - "profile-".length());
        String apiPrefix = "http://localhost:18080/api/v1/";
        String apiBase = apiPrefix + "a".repeat(valueLength - apiPrefix.length());

        List<String> lines = printer.buildBanner(profile, apiBase).lines().toList();
        int profileStart = lines.indexOf("QingNiao0x") + 1;
        int apiStart = profileStart + expectedRows;

        assertInfoRows(lines.subList(profileStart, apiStart), "Profile : ", profile, expectedRows);
        assertInfoRows(lines.subList(apiStart, lines.size()), "API     : ", apiBase, expectedRows);
        assertTrue(lines.stream().allMatch(line -> line.length() <= 100), "长运行信息也应遵守 100 字符行宽");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onApplicationReadyShouldLogConfiguredOrDefaultRuntimeInfo(boolean configured) {
        MockEnvironment environment = new MockEnvironment();
        if (configured) {
            environment.setActiveProfiles("local", "sql-debug");
            environment.setProperty("server.port", "19000");
        }
        StartupBannerPrinter printer = new StartupBannerPrinter(environment);
        Logger logger = (Logger) LoggerFactory.getLogger(StartupBannerPrinter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            printer.onApplicationReady();

            assertEquals(1, appender.list.size(), "一次就绪回调应只输出一条完整横幅日志");
            ILoggingEvent event = appender.list.getFirst();
            String expectedProfile = configured ? "local,sql-debug" : "default";
            String expectedApiBase = "http://localhost:" + (configured ? "19000" : "8080") + "/api/v1";
            assertEquals(Level.INFO, event.getLevel(), "启动横幅使用 INFO 日志级别");
            assertEquals("\n" + printer.buildBanner(expectedProfile, expectedApiBase), event.getFormattedMessage(),
                    "图案应从日志前缀后的新行开始，并保留 Profile 顺序和端口回退");
        } finally {
            // 只解除本测试的监听器，避免后续测试受到全局日志对象的影响。
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 校验长信息折行后仍可完整恢复，且续行从标签之后的第 11 列开始。 */
    private static void assertInfoRows(List<String> rows, String prefix, String value, int expectedRows) {
        assertEquals(expectedRows, rows.size(), "90 字符有效载荷正好一行，第 91 个字符应换行");
        assertTrue(rows.getFirst().startsWith(prefix), "信息首行应保留标签");
        for (String continuation : rows.subList(1, rows.size())) {
            assertEquals(10, continuation.length() - continuation.stripLeading().length(), "续行应缩进 10 个空格");
        }
        assertEquals(value, rows.stream().map(line -> line.substring(10)).collect(Collectors.joining()),
                "折行不应省略或改写运行信息");
    }
}
