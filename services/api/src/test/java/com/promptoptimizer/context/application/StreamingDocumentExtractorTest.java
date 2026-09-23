package com.promptoptimizer.context.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证实际文档解析器的中文连续性与大段落内存边界，样例全部在临时目录生成。 */
class StreamingDocumentExtractorTest {
    @TempDir
    Path directory;

    private final StreamingDocumentExtractor extractor = new StreamingDocumentExtractor(new BinaryContentExtractor());

    @Test
    void shouldPreserveChineseWordsAcrossWordFormattingRunsAndReadTableAndFooter() throws Exception {
        Path source = directory.resolve("sample.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            putXml(zip, "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body><w:p><w:r><w:t>心脑</w:t></w:r><w:r><w:t>血管疾病死亡率</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>地区：浙江</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>年份：2015—2025</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:p><w:r><w:t xml:space="preserve">YLL rate </w:t></w:r><w:r><w:t>per 100000</w:t></w:r></w:p>
                    </w:body></w:document>
                    """);
            putXml(zip, "word/footer1.xml", """
                    <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:p><w:r><w:t>尾注：采用Arriaga分解</w:t></w:r></w:p></w:ftr>
                    """);
        }
        List<String> sections = new ArrayList<>();
        var report = extractor.extract(source, "sample.docx", "docx", (label, text) -> sections.add(text), (a, b) -> {});
        assertThat(String.join("\n", sections)).contains("心脑血管疾病死亡率", "地区：浙江",
                "年份：2015—2025", "YLL rate per 100000", "采用Arriaga分解");
        assertThat(report.complete()).isTrue();
    }

    @Test
    void shouldStreamLongTextWithoutRequiringLineBreaks() throws Exception {
        String content = "中文连续正文".repeat(100_000) + "尾部：退费期限三个工作日";
        Path source = directory.resolve("long-line.txt");
        Files.writeString(source, content, StandardCharsets.UTF_8);
        List<String> sections = new ArrayList<>();
        extractor.extract(source, "long-line.txt", "text", (label, text) -> sections.add(text), (a, b) -> {});
        assertThat(sections.stream().mapToInt(String::length).max().orElse(0)).isLessThanOrEqualTo(16_000);
        assertThat(String.join("", sections)).isEqualTo(content);
    }

    /** 仅构造解析器使用的 OOXML 正文条目，不依赖外部用户文件或 Office 安装。 */
    private void putXml(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
