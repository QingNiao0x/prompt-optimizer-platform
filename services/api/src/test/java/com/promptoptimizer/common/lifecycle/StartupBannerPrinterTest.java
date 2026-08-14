package com.promptoptimizer.common.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动横幅内容与格式校验。
 *
 * @DateTime: 2026-08-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证启动成功时打印的 ASCII 图案包含关键信息和占位替换结果。
 * @since 0.1.0
 */
class StartupBannerPrinterTest {

    @Test
    void buildBannerShouldContainArtworkProfileAndApiBase() {
        StartupBannerPrinter printer = new StartupBannerPrinter(null);

        String banner = printer.buildBanner("default", "http://localhost:18080/api/v1");

        assertTrue(banner.contains(">----"), "应包含青鸟的向右鸟喙");
        assertTrue(banner.contains("~~~~"), "应包含参考图中的展开翅膀线条");
        assertTrue(banner.contains("_..---"), "应包含青鸟的飞行轮廓");
        assertTrue(banner.contains("QingNiao0x"), "应包含 GitHub 用户名");
        assertTrue(banner.contains("Profile : default"), "应包含当前 Profile");
        assertTrue(banner.contains("API     : http://localhost:18080/api/v1"), "应包含 API 地址");
        assertTrue(banner.indexOf(">----") < banner.indexOf("| |_| |"), "青鸟图案应位于字符画之前");
        assertTrue(banner.indexOf("| |_| |") < banner.indexOf("QingNiao0x"), "用户名应位于字符画之后");
        assertTrue(banner.indexOf("QingNiao0x") < banner.indexOf("Profile : default"), "用户名应位于 Profile 上方");
        assertTrue(banner.lines().anyMatch(line -> line.equals("QingNiao0x")), "用户名应顶格显示");
        assertTrue(banner.lines().anyMatch(line -> line.equals("Profile : default")), "Profile 应顶格显示");
        assertTrue(banner.lines().allMatch(line -> line.length() <= 100), "图案每行宽度不应超过 100");
    }

    @Test
    void buildBannerShouldUseBlankProfileFallback() {
        StartupBannerPrinter printer = new StartupBannerPrinter(null);

        String banner = printer.buildBanner("", "http://localhost:8080/api/v1");

        assertTrue(banner.contains("Profile : default"), "空 Profile 应回退为 default");
        assertEquals(1, banner.lines().filter(line -> line.contains("Profile :")).count(), "Profile 只应出现一次");
    }
}
