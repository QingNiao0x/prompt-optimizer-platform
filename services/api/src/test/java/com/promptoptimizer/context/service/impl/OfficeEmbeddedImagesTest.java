package com.promptoptimizer.context.service.impl;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static com.promptoptimizer.context.service.impl.OfficeContextFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** 只枚举活跃正文引用的图片，不访问网络或本机包外路径。 */
class OfficeEmbeddedImagesTest {
    @TempDir Path directory;

    @Test void wordDeduplicatesUsedImagesAndIgnoresUnreferencedMedia() throws Exception {
        byte[] pixels = ContextVisualDocumentWorkflowTest.png();
        Path source = directory.resolve("reference.docx");
        Files.write(source, wordWithImage("参考正文", pixels));
        var catalog = OfficeEmbeddedImages.inspect(source, "docx", 500);
        assertThat(catalog.images()).hasSize(1);
        assertThat(catalog.images().getFirst().label()).startsWith("内嵌图片 1");
        assertThat(OfficeEmbeddedImages.bytes(source, catalog.images().getFirst())).isEqualTo(pixels);
        assertThat(catalog.warnings()).isEmpty();
    }

    @Test void spreadsheetAndPresentationFollowActualDrawingRelationships() throws Exception {
        byte[] pixels = ContextVisualDocumentWorkflowTest.png();
        Path spreadsheet = directory.resolve("book.xlsx");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(spreadsheet)) {
            var sheet = book.createSheet("研究表");
            sheet.createRow(0).createCell(0).setCellValue("原始数据");
            var anchor = book.getCreationHelper().createClientAnchor();
            anchor.setRow1(1); anchor.setCol1(1);
            sheet.createDrawingPatriarch().createPicture(anchor, book.addPicture(pixels, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            book.write(output);
        }
        assertThat(OfficeEmbeddedImages.inspect(spreadsheet, "xlsx", 500).images()).hasSize(1);
        Path presentation = directory.resolve("deck.pptx");
        try (var deck = new XMLSlideShow(); var output = Files.newOutputStream(presentation)) {
            var slide = deck.createSlide();
            slide.createPicture(deck.addPicture(pixels, PictureData.PictureType.PNG));
            deck.write(output);
        }
        assertThat(OfficeEmbeddedImages.inspect(presentation, "pptx", 500).images()).hasSize(1);
    }

    @Test void openDocumentUsesInternalHrefAndReportsExternalImages() throws Exception {
        Path source = directory.resolve("reference.odt");
        Files.write(source, packageBytes(Map.of("content.xml", xml("""
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                 xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:xlink="http://www.w3.org/1999/xlink">
                <draw:image xlink:href="Pictures/picture.png"/><draw:image xlink:href="https://example.invalid/external.png"/>
                </office:document-content>
                """), "Pictures/picture.png", ContextVisualDocumentWorkflowTest.png())));
        var catalog = OfficeEmbeddedImages.inspect(source, "odt", 500);
        assertThat(catalog.images()).hasSize(1);
        assertThat(catalog.warnings()).anyMatch(value -> value.contains("外链"));
    }

    @Test void deletedImagesExternalRelationshipsAndTraversalNeverBecomeSelectablePixels() throws Exception {
        Path source = directory.resolve("external.docx");
        Files.write(source, packageBytes(Map.of("word/document.xml", xml("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <w:del><a:blip r:embed="deleted"/></w:del><a:blip r:embed="outside"/><a:blip r:embed="traversal"/>
                </w:document>
                """), "word/_rels/document.xml.rels", xml("""
                <Relationships><Relationship Id="outside" Type="x/image" Target="https://example.invalid/a.png" TargetMode="External"/>
                <Relationship Id="traversal" Type="x/image" Target="../../a.png"/>
                <Relationship Id="deleted" Type="x/image" Target="media/deleted.png"/></Relationships>
                """), "word/media/deleted.png", ContextVisualDocumentWorkflowTest.png())));
        var catalog = OfficeEmbeddedImages.inspect(source, "docx", 500);
        assertThat(catalog.images()).isEmpty();
        assertThat(catalog.warnings()).anyMatch(value -> value.contains("外链"));
    }

    @Test void documentImageLimitsAndMissingAssetsRemainVisibleAsOmissions() throws Exception {
        Path source = directory.resolve("limited.docx");
        Files.write(source, packageBytes(Map.of("word/document.xml", xml("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <a:blip r:embed="one"/><a:blip r:embed="two"/></w:document>
                """), "word/_rels/document.xml.rels", xml("""
                <Relationships><Relationship Id="one" Type="x/image" Target="media/missing.png"/>
                <Relationship Id="two" Type="x/image" Target="media/second.png"/></Relationships>
                """))));
        var catalog = OfficeEmbeddedImages.inspect(source, "docx", 1);
        assertThat(catalog.images()).hasSize(1);
        assertThat(catalog.images().getFirst().blockedReason()).contains("缺失");
        assertThat(catalog.warnings()).anyMatch(value -> value.contains("上限"));
    }

    @Test void packageXmlCannotResolveExternalEntities() throws Exception {
        Path source = directory.resolve("unsafe.odt");
        Files.write(source, packageBytes(Map.of("content.xml", xml("<!DOCTYPE content [<!ENTITY x SYSTEM 'file:///unavailable'>]><content>&x;</content>"))));
        assertThatThrownBy(() -> OfficeEmbeddedImages.inspect(source, "odt", 500)).isInstanceOf(IllegalArgumentException.class);
    }
}
