package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.context.service.DocumentIndexLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.DocumentSelection;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextAnalyzerTest {

    @Test
    void shouldRetrieveTheSameDocumentOnlyOnceEvenWhenItHasDifferentDisplayPaths() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentIndexLookup lookup = (id, query, characters, chunks) -> {
            calls.incrementAndGet();
            return java.util.Optional.of(new DocumentSelection("rules.txt", "text", "审批阈值五万元",
                    "业务规则", 30, 8, 1, 1, 8, false, List.of()));
        };
        var analyzer = new DefaultContextAnalyzer(new ObjectMapper(), new BinaryContentExtractor(),
                new FileContentSummarizer(), lookup);
        var snapshot = analyzer.analyze(new ContextAnalysisRequest("", List.of(
                new ContextFileInput("rules.txt", "", "text", "same-document", 30L),
                new ContextFileInput("docs/rules.txt", "", "text", "same-document", 30L))), "审批阈值");
        assertThat(calls).hasValue(1);
        assertThat(snapshot.fileSnippets()).hasSize(1);
        assertThat(snapshot.warnings()).contains("已忽略重复文档引用：docs/rules.txt");
    }

    private final DefaultContextAnalyzer analyzer = new DefaultContextAnalyzer(
            new ObjectMapper(),
            new BinaryContentExtractor(),
            new FileContentSummarizer()
    );

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
                        new ContextFileInput(".env", "MODEL_API_KEY=secret-value", null),
                        new ContextFileInput("certificates/client.pem", "private key", null),
                        new ContextFileInput("config/application-prod.yml", "password: production-secret", null),
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
        assertThat(snapshot.warnings()).anyMatch(message -> message.contains("已忽略受保护文件"));
        assertThat(snapshot.redactions())
                .contains(".env", "certificates/client.pem", "config/application-prod.yml");
    }

    @Test
    void shouldExtractWordTextFromBase64Document() throws Exception {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("登录接口必须校验空值和重复用户名。");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        document.close();
        String base64 = Base64.getEncoder().encodeToString(output.toByteArray());

        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "",
                List.of(new ContextFileInput("docs/登录需求.docx", base64, "docx"))
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.fileSnippets()).hasSize(1);
        assertThat(snapshot.fileSnippets().get(0).language()).isEqualTo("docx");
        assertThat(snapshot.fileSnippets().get(0).content()).contains("登录接口");
        assertThat(snapshot.fileSnippets().get(0).summary()).contains("登录接口");
        assertThat(snapshot.warnings()).isEmpty();
    }

    @Test
    void shouldDetectTechnologyFromIndexedChunkPaths() {
        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "",
                List.of(
                        new ContextFileInput("services/api/pom.xml#chunk-1", """
                                <project>
                                  <parent><artifactId>spring-boot-starter-parent</artifactId></parent>
                                  <dependencies>
                                    <dependency>
                                      <groupId>org.postgresql</groupId>
                                      <artifactId>postgresql</artifactId>
                                    </dependency>
                                    <dependency>
                                      <groupId>org.springframework.boot</groupId>
                                      <artifactId>spring-boot-starter-data-redis</artifactId>
                                    </dependency>
                                  </dependencies>
                                </project>
                                """, "xml"),
                        new ContextFileInput("apps/web/package.json#chunk-1", """
                                {
                                  "dependencies": {"vue": "^3.5.0"},
                                  "devDependencies": {"typescript": "^5.7.0", "vite": "^6.0.0"}
                                }
                                """, "json"),
                        new ContextFileInput(
                                "services/api/src/main/java/App.java#chunk-1",
                                "import org.springframework.boot.autoconfigure.SpringBootApplication; class App {}",
                                "java"
                        ),
                        new ContextFileInput(
                                "apps/web/src/App.vue#chunk-1",
                                "<script setup lang=\"ts\">const title: string = 'Prompt Optimizer';</script>",
                                "vue"
                        )
                )
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.technologyStack()).extracting("name")
                .contains(
                        "Java",
                        "Spring Boot",
                        "Node.js",
                        "Vue",
                        "TypeScript",
                        "Vite",
                        "PostgreSQL",
                        "Redis"
                );
        assertThat(snapshot.technologyStack())
                .allMatch(item -> !item.source().contains("#chunk-"));
    }

    @Test
    void shouldCreateReadableSummaryForTextAndWarnWhenContentIsEmpty() {
        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "",
                List.of(
                        new ContextFileInput(
                                "docs/研究说明.txt",
                                "研究目的：评估项目上下文对提示词质量的影响。\n"
                                        + "研究方法：比较有上下文和无上下文的输出。\n"
                                        + "研究结论：相关文件检索能够减少无关信息。",
                                "text"
                        ),
                        new ContextFileInput("docs/空白说明.txt", "   ", "text")
                )
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.fileSnippets()).hasSize(1);
        assertThat(snapshot.fileSnippets().get(0).summary())
                .contains("研究目的")
                .contains("相关文件检索");
        assertThat(snapshot.warnings())
                .contains("文件内容为空，无法生成摘要：docs/空白说明.txt");
    }

    @Test
    void shouldSummarizeRepresentativeContentFromBeginningMiddleAndEnd() {
        String longDocument = "文档开头：研究大型上下文解析。\n"
                + "普通背景材料。\n".repeat(4_000)
                + "文档中部标记：MID-RISK-CONTROL。\n"
                + "普通实施说明。\n".repeat(4_000)
                + "文档结尾标记：TAIL-ACCEPTANCE-2026。";
        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "",
                List.of(new ContextFileInput("docs/大型研究说明.txt", longDocument, "text"))
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.fileSnippets()).hasSize(1);
        assertThat(snapshot.fileSnippets().get(0).summary())
                .contains("文档开头")
                .contains("MID-RISK-CONTROL")
                .contains("TAIL-ACCEPTANCE-2026");
    }

    @Test
    void shouldCreateImageMetadataSummaryAndOcrWarning() throws Exception {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);

        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "",
                List.of(new ContextFileInput(
                        "images/context-preview.png",
                        Base64.getEncoder().encodeToString(output.toByteArray()),
                        "png"
                ))
        );

        ContextSnapshot snapshot = analyzer.analyze(request);

        assertThat(snapshot.fileSnippets()).hasSize(1);
        assertThat(snapshot.fileSnippets().get(0).summary())
                .contains("图片元数据")
                .contains("120")
                .contains("80");
        assertThat(snapshot.warnings())
                .contains("图片当前仅提取元数据，尚未进行 OCR 或视觉识别：images/context-preview.png");
    }

    @Test
    void shouldResolveTemporaryDocumentReferenceAndExposeCoverage() {
        DocumentIndexLookup lookup = (documentId, query, maxCharacters, maxChunks) ->
                java.util.Optional.of(new DocumentSelection(
                        "docs/大型报告.txt",
                        "text",
                        "[文档片段 18/20]\n结论：TAIL-ACCEPTANCE-2026 必须支持断点续传。",
                        "报告覆盖背景、实施方案和最终验收要求。",
                        12_000_000,
                        9_800_000,
                        20,
                        4,
                        24_000,
                        true,
                        List.of()
                ));
        DefaultContextAnalyzer indexedAnalyzer = new DefaultContextAnalyzer(
                new ObjectMapper(),
                new BinaryContentExtractor(),
                new FileContentSummarizer(),
                lookup
        );
        ContextAnalysisRequest request = new ContextAnalysisRequest(
                "",
                List.of(new ContextFileInput(
                        "docs/大型报告.txt",
                        "",
                        "text",
                        "document-123",
                        12_000_000L
                ))
        );

        ContextSnapshot snapshot = indexedAnalyzer.analyze(request, "提取 TAIL-ACCEPTANCE-2026 验收要求");

        assertThat(snapshot.analysisStatus()).isEqualTo("COMPLETE");
        assertThat(snapshot.fileSnippets()).singleElement()
                .satisfies(snippet -> assertThat(snippet.content()).contains("TAIL-ACCEPTANCE-2026"));
        assertThat(snapshot.fileCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.extractedCharacters()).isEqualTo(9_800_000);
            assertThat(coverage.indexedChunks()).isEqualTo(20);
            assertThat(coverage.selectedChunks()).isEqualTo(4);
            assertThat(coverage.contextLimited()).isTrue();
        });
    }
}
