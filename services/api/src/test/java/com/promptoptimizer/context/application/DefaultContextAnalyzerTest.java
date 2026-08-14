package com.promptoptimizer.context.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.api.ContextFileInput;
import com.promptoptimizer.context.domain.ContextSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextAnalyzerTest {

    private final DefaultContextAnalyzer analyzer = new DefaultContextAnalyzer(new ObjectMapper());

    @Test
    void shouldDetectStackDependenciesAndDirectoryTree() {
        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "这是一个前后端分离项目，优先保证可读性。",
                List.of(
                        new ContextFileInput("backend/pom.xml", """
                                <project>
                                  <dependencies>
                                    <dependency>
                                      <groupId>org.springframework.boot</groupId>
                                      <artifactId>spring-boot-starter-web</artifactId>
                                      <version>3.3.13</version>
                                    </dependency>
                                    <dependency>
                                      <groupId>org.postgresql</groupId>
                                      <artifactId>postgresql</artifactId>
                                    </dependency>
                                  </dependencies>
                                  <parent>spring-boot</parent>
                                </project>
                                """, "xml"),
                        new ContextFileInput("frontend/package.json", """
                                {
                                  "dependencies": {"vue": "^3.5.0", "element-plus": "^2.8.4"},
                                  "devDependencies": {"typescript": "^5.6.3", "vite": "^5.4.10"}
                                }
                                """, "json"),
                        new ContextFileInput("frontend/src/App.vue", "<template><div>Hello</div></template>", "vue")
                )
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.customDescription()).contains("前后端分离");
        assertThat(snapshot.technologyStack()).extracting("name")
                .contains("Java", "Spring Boot", "Node.js", "Vue", "TypeScript", "Vite", "PostgreSQL");
        assertThat(snapshot.dependencies()).extracting("name")
                .contains("org.springframework.boot:spring-boot-starter-web", "org.postgresql:postgresql", "vue", "typescript");
        assertThat(snapshot.directoryTree()).contains("backend/", "backend/pom.xml", "frontend/src/");
        assertThat(snapshot.fileSnippets()).hasSize(3);
    }

    @Test
    void shouldRejectAbsoluteTraversalAndSensitivePaths() {
        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "项目描述中不应出现 password=secret-value",
                List.of(
                        new ContextFileInput("C:/Users/example/.env", "API_KEY=sk-1234567890", null),
                        new ContextFileInput("../outside.txt", "outside", null),
                        new ContextFileInput(".git/config", "repository", null),
                        new ContextFileInput("src/main/App.java", "class App {}", null)
                )
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.fileSnippets()).extracting("path").containsExactly("src/main/App.java");
        assertThat(snapshot.redactions()).contains("customDescription");
        assertThat(snapshot.warnings()).anyMatch(message -> message.contains("不安全或无效路径"));
        assertThat(snapshot.warnings()).anyMatch(message -> message.contains("生成目录或工具目录"));
    }
}
