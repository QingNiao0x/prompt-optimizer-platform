package com.promptoptimizer.common.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后端启动成功后打印 ASCII 启动图案。
 *
 * @DateTime: 2026-08-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 监听 ApplicationReadyEvent，在应用完全就绪后于控制台打印启动图案与关键信息，便于确认服务启动成功。
 * @since 0.1.0
 */
@Component
public class StartupBannerPrinter {

    private static final Logger log = LoggerFactory.getLogger(StartupBannerPrinter.class);

    private static final int LETTER_ROWS = 5;
    private static final String GITHUB_USERNAME = "QingNiao0x";

    /**
     * 根据第 1 张参考图绘制的原创 ASCII 青鸟：突出展开的翅膀、鸟身和向右的鸟喙。
     *
     * <p>这里使用纯 ASCII 字符，避免不同终端对宽字符渲染不一致。</p>
     */
    private static final String[] BIRD = {
            "                         _..---~~~~---.._",
            "                  _..--''      _.._      `--.._",
            "             _.-'          _.-'   `-._         `-._",
            "        _.-''       _..--''  .--.    `--.._       `.",
            "   _.-'      _..--''        .'    `.        `--.._  \\",
            " .'   _..--''       _..---/  .--.   \\---.._      `-.>----",
            "/_.-''       _..---'     |  |  |     |     `---.._  \\",
            "\\__..---'''             `-.`-' .-'              `-./",
            "                           `---'       __..---''",
            "                 _..---.        __..--''",
            "             _.-'      `--..--''",
            "          .-'       _..---.._",
            "        .'      _.-'         `-._",
            "       /_____.-'               `-._",
    };

    /*
     * 第 2 张参考图：侧身飞鸟版本。先保留在这里，后续可以做成可配置的启动图案。
     *
     * private static final String[] BIRD_OPTION_TWO = {
     *         "                 /\\",
     *         "        ________/  \\________",
     *         "   ____/                       `--.._",
     *         "  /        .--.        _..---.      `--..__",
     *         " <        /    \\____.'       `-.          `>----",
     *         "  \\______/                         `-..__  /",
     *         "        `--..____________________________/"
     * };
     *
     * 第 3 张参考图：线稿展翅版本。
     *
     * private static final String[] BIRD_OPTION_THREE = {
     *         "                   /\\",
     *         "          __..---'  `---..__",
     *         "     _.-'      _.._       `-._",
     *         "  .-'        .'    `.         `-.",
     *         " /     _..--'  /\\   `--.._     \\",
     *         "|  _.-'      /  \\       `-._  |>----",
     *         " \\/          `--'           `-./",
     *         "  `--..__              __..--'",
     *         "         `---..____..---'"
     * };
     */

    /**
     * 用户名字符画的字母模板，每行宽度对齐到该字母的最大宽度。
     */
    private static final Map<Character, String[]> LETTERS = buildLetters();

    private final Environment environment;

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
     * 组装启动图案：展翅青鸟 + GitHub 用户名字符画 + 运行信息。
     */
    String buildBanner(String profile, String apiBase) {
        if (profile.isBlank()) {
            profile = "default";
        }

        String wordArt = renderWord("QINGNIAO0X");
        int width = maxLineWidth(wordArt);
        String birdIndent = " ".repeat((width - maxLineWidth(BIRD)) / 2);
        String birdArt = Arrays.stream(BIRD)
                .map(line -> birdIndent + line)
                .collect(Collectors.joining("\n"));

        return birdArt + "\n\n"
                + wordArt + "\n\n"
                + GITHUB_USERNAME + "\n"
                + "Profile : " + profile + "\n"
                + "API     : " + apiBase;
    }

    /**
     * 把字母模板按行拼接成整词字符画。
     */
    private static String renderWord(String word) {
        StringBuilder result = new StringBuilder();
        for (int row = 0; row < LETTER_ROWS; row++) {
            for (int i = 0; i < word.length(); i++) {
                String[] letter = LETTERS.get(word.charAt(i));
                if (letter == null) {
                    throw new IllegalArgumentException("不支持的字符: " + word.charAt(i));
                }
                result.append(letter[row]);
                if (i < word.length() - 1) {
                    result.append("  ");
                }
            }
            if (row < LETTER_ROWS - 1) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private static int maxLineWidth(String block) {
        return block.lines().mapToInt(String::length).max().orElse(0);
    }

    private static int maxLineWidth(String[] block) {
        return Arrays.stream(block).mapToInt(String::length).max().orElse(0);
    }

    private static Map<Character, String[]> buildLetters() {
        Map<Character, String[]> letters = new LinkedHashMap<>();
        letters.put('Q', new String[]{"  ___  ", " / _ \\ ", "| | | |", "| |_| |", " \\__\\_\\"});
        letters.put('I', new String[]{"  ___  ", " |_ _| ", "  | |  ", "  | |  ", " |___| "});
        letters.put('N', new String[]{" _   _ ", "| \\ | |", "|  \\| |", "| |\\  |", "|_| \\_|"});
        letters.put('G', new String[]{"  ____ ", " / ___|", "| |  _ ", "| |_| |", " \\____|"});
        letters.put('A', new String[]{"    _    ", "   / \\   ", "  / _ \\  ", " / ___ \\ ", "/_/   \\_\\"});
        letters.put('O', new String[]{"  ___  ", " / _ \\ ", "| | | |", "| |_| |", " \\___/ "});
        letters.put('0', new String[]{"  ___  ", " / _ \\ ", "|  _  |", "| | | |", " \\___/ "});
        letters.put('X', new String[]{" _  _ ", "| || |", "| __ |", "| || |", "|_||_|"});
        return letters;
    }
}
