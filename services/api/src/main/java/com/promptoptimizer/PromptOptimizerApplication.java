package com.promptoptimizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 提示词优化平台后端启动入口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootApplication
public class PromptOptimizerApplication {

    /**
     * 启动 Spring Boot 应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(PromptOptimizerApplication.class, args);
    }
}
