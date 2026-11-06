package com.promptoptimizer.context.service.impl;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void wordStyleInheritanceAndDirectParagraphFormattingRemainAdjacentToText() throws Exception {
        Path source = directory.resolve("format.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            putXml(zip, "word/styles.xml", """
                    <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:style w:type="paragraph" w:styleId="Normal" w:default="1"><w:name w:val="正文"/>
                    <w:pPr><w:ind w:left="720"/><w:jc w:val="both"/></w:pPr></w:style>
                    <w:style w:type="paragraph" w:styleId="Report"><w:name w:val="报告段落"/><w:basedOn w:val="Normal"/>
                    <w:pPr><w:ind w:firstLine="480"/><w:jc w:val="center"/></w:pPr></w:style>
                    <w:style w:type="table" w:styleId="Table"><w:tblStylePr w:type="firstRow">
                    <w:pPr><w:spacing w:after="999"/></w:pPr></w:tblStylePr></w:style></w:styles>
                    """);
            putXml(zip, "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                    <w:p><w:pPr><w:pStyle w:val="Report"/><w:jc w:val="right"/><w:ind w:hanging="240"/></w:pPr>
                    <w:r><w:t>参考</w:t></w:r><w:r><w:t>格式正文</w:t></w:r></w:p>
                    <w:p><w:r><w:t>尾部条款</w:t></w:r></w:p></w:body></w:document>
                    """);
        }
        var sections = new ArrayList<String>();
        var report = extractor.extract(source, "format.docx", "docx", (label, text) -> sections.add(text), (a, b) -> { });
        assertThat(sections.getFirst()).contains("样式=Report（报告段落）", "左缩进(twip)=720", "对齐=right", "悬挂缩进(twip)=240", "参考格式正文")
                .doesNotContain("首行缩进", "对齐=center", "段后(twip)=999");
        assertThat(sections.getLast()).contains("样式=Normal（正文）", "对齐=both", "尾部条款");
        assertThat(report.complete()).isTrue();
    }

    @Test
    void cyclicStylesRemainPartialWithoutLosingTextAndLongParagraphTail() throws Exception {
        Path source = directory.resolve("cycle.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            putXml(zip, "word/styles.xml", """
                    <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:style w:type="paragraph" w:styleId="a"><w:basedOn w:val="b"/></w:style>
                    <w:style w:type="paragraph" w:styleId="b"><w:basedOn w:val="a"/></w:style></w:styles>
                    """);
            putXml(zip, "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body><w:p><w:pPr><w:pStyle w:val="a"/></w:pPr><w:r><w:t>%s尾部仍需保留</w:t></w:r></w:p></w:body></w:document>
                    """.formatted("连续正文".repeat(20_000)));
        }
        var sections = new ArrayList<String>();
        var report = extractor.extract(source, "cycle.docx", "docx", (label, text) -> sections.add(text), (a, b) -> { });
        assertThat(String.join("", sections)).contains("连续正文".repeat(20_000) + "尾部仍需保留");
        assertThat(report.complete()).isFalse();
        assertThat(report.warnings()).anyMatch(value -> value.contains("循环"));
    }

    @Test
    void styleXmlCannotResolveExternalEntities() throws Exception {
        Path source = directory.resolve("unsafe.docx");
        Files.write(source, OfficeContextFixtures.packageBytes(java.util.Map.of(
                "word/document.xml", OfficeContextFixtures.xml("<document/>"),
                "word/styles.xml", OfficeContextFixtures.xml("<!DOCTYPE styles [<!ENTITY x SYSTEM 'file:///unavailable'>]><styles>&x;</styles>"))));
        assertThatThrownBy(() -> extractor.extract(source, "unsafe.docx", "docx", (a, b) -> { }, (a, b) -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 仅构造解析器使用的 OOXML 正文条目，不依赖外部用户文件或 Office 安装。 */
    private void putXml(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
