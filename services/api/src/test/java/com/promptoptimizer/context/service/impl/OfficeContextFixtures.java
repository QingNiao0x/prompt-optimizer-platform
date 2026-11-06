package com.promptoptimizer.context.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 合成包只包含回归所需的文字、样式和图片，不读取用户文档。 */
final class OfficeContextFixtures {
    private OfficeContextFixtures() { }

    /** 相同图片被正文引用两次，另有未引用图片，验证目录按引用去重。 */
    static byte[] wordWithImage(String text, byte[] image) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("word/document.xml", xml("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <w:body><w:p><w:pPr><w:jc w:val="center"/><w:ind w:firstLine="480"/></w:pPr>
                <w:r><w:t>%s</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="picture"/></w:drawing></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="picture"/></w:drawing></w:r></w:p>
                </w:body></w:document>
                """.formatted(text)));
        entries.put("word/_rels/document.xml.rels", xml("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="picture" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/picture.png"/>
                </Relationships>
                """));
        entries.put("word/media/picture.png", image);
        entries.put("word/media/not-referenced.png", image);
        return packageBytes(entries);
    }

    static byte[] xml(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    /** 写入内存包，所有路径由测试常量提供。 */
    static byte[] packageBytes(Map<String, byte[]> entries) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
