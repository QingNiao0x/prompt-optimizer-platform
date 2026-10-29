package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.service.impl.*;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.Track;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 本地合成资料验证范围、隐私、真实解析及过期授权；没有模型和外网调用。 */
class ImagePromptSourceServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    private ImagePromptSourceService service;
    @BeforeEach void setup() {
        var states = new ImagePromptStateStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), json, "MEMORY");
        service = new ImagePromptSourceService(states, new StreamingDocumentExtractor(new BinaryContentExtractor()), json);
    }

    @Test void codePreparationPreservesTailAndRedactsCredentialsBeforePreview() {
        String text = "export const value = 1;\n".repeat(400) + "password='fixtureActualCredential123'\n// 尾部要求：保留权限校验";
        var asset = service.upload(actor, file("Order.vue", text));
        assertThat(asset.kind()).isEqualTo(Kind.CODE);
        assertThat(asset.status()).isEqualTo(PreparationStatus.READY);
        assertThat(asset.units()).hasSizeGreaterThan(1);
        assertThat(asset.units().stream().map(Unit::text).reduce("", String::concat)).contains("尾部要求：保留权限校验", "REDACTED").doesNotContain("fixtureActualCredential123");
        assertThat(asset.warnings()).anyMatch(value -> value.contains("脱敏"));
    }

    @Test void htmlSnapshotDoesNotExecuteScriptsOrIncludeTheirText() {
        var asset = service.text(actor, new TextUpload("网页快照", List.of(new Entry("page.html",
                "<html><body><h1>订单管理</h1><script>fetch('https://private.invalid');</script><p>点击保存</p></body></html>"))));
        assertThat(asset.units().stream().map(Unit::text).reduce("", String::concat)).contains("订单管理", "点击保存").doesNotContain("fetch", "private.invalid");
    }

    @Test void officeTextAndPdfUseActualParsersAndEmptyPdfExplainsMissingTextLayer() throws Exception {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Order API must preserve authorization and validation.");
            document.write(bytes);
            var result = service.upload(actor, new MockMultipartFile("file", "requirements.docx", "application/octet-stream", bytes.toByteArray()));
            assertThat(result.kind()).isEqualTo(Kind.DOCUMENT);
            assertThat(result.units()).anySatisfy(unit -> assertThat(unit.text()).contains("preserve authorization"));
        }
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700); content.showText("Preserve the order API contract."); content.endText();
            }
            document.save(bytes);
            var result = service.upload(actor, new MockMultipartFile("file", "contract.pdf", "application/pdf", bytes.toByteArray()));
            assertThat(result.units()).anySatisfy(unit -> assertThat(unit.text()).contains("order API contract"));
        }
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(bytes);
            assertThatThrownBy(() -> service.upload(actor, new MockMultipartFile("file", "scan.pdf", "application/pdf", bytes.toByteArray())))
                    .isInstanceOf(ImagePromptException.class).hasMessageContaining("文字层");
        }
    }

    @Test void credentialsAcrossOfficeExtractionBlocksAreRedactedBeforePreview() throws Exception {
        // Word文字块按16000字符输出，凭据字段和值跨解析回调仍属于同一段原文。
        String text = "x".repeat(15992) + " password='crossBlockPrivateValue12345'";
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text); document.write(bytes);
            var result = service.upload(actor, new MockMultipartFile("file", "boundary.docx", "application/octet-stream", bytes.toByteArray()));
            assertThat(result.units().stream().map(Unit::text).reduce("", String::concat))
                    .contains("REDACTED").doesNotContain("crossBlockPrivateValue12345");
        }
    }

    @Test void collectionsRejectProtectedPathsAndUnknownBinaryBeforeSaving() {
        for (String path : List.of("../escape.ts", "C:/private.ts", ".git/config", "app/.env", "config/prod.yml", "application-prod.yaml", "id_rsa")) {
            assertThatThrownBy(() -> service.text(actor, new TextUpload("集合", List.of(new Entry(path, "plain text")))))
                    .isInstanceOf(ImagePromptException.class);
        }
        assertThatThrownBy(() -> service.upload(actor, file("sample.exe", "binary"))).isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> service.upload(actor, file("sample.txt", "nul\0bytes"))).isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> service.text(actor, new TextUpload("集合", List.of(new Entry("same.ts", "a"), new Entry("same.ts", "b")))))
                .isInstanceOf(ImagePromptException.class);
    }

    @Test void preparedBudgetReportsPartialAndDoesNotClaimWholeCollectionRead() {
        var asset = service.text(actor, new TextUpload("长文集合", List.of(new Entry("a.txt", "a".repeat(400000)), new Entry("b.txt", "b".repeat(400000)))));
        assertThat(asset.status()).isEqualTo(PreparationStatus.PARTIAL);
        assertThat(asset.units().stream().mapToInt(unit -> unit.text().length()).sum()).isEqualTo(600000);
        assertThat(asset.warnings()).anyMatch(value -> value.contains("后续"));
    }

    @Test void identityAndSelectedUnitsAreAlwaysServerValidated() {
        var asset = service.upload(actor, file("notes.md", "content"));
        for (var other : List.of(
                new ActorIdentity(actor.userId(), UUID.randomUUID(), actor.workspaceId(), actor.email(), actor.displayName()),
                new ActorIdentity(actor.userId(), actor.tenantId(), UUID.randomUUID(), actor.email(), actor.displayName()),
                new ActorIdentity(UUID.randomUUID(), actor.tenantId(), actor.workspaceId(), actor.email(), actor.displayName()))) {
            assertThatThrownBy(() -> service.require(other, asset.id())).isInstanceOf(ImagePromptException.class);
        }
        var task = new Task(TaskType.DOCUMENT, List.of(new Reference(asset.id(), List.of(UUID.randomUUID()), Track.REFERENCE, Role.FACTS)), null);
        assertThatThrownBy(() -> service.resolve(actor, task)).isInstanceOf(ImagePromptException.class);
        service.revoke(actor, asset.id());
        assertThatThrownBy(() -> service.require(actor, asset.id())).isInstanceOf(ImagePromptException.class);
    }

    @Test void consentBindsModelGoalModeAndSelectedRanges() {
        var asset = service.upload(actor, file("notes.md", "Source facts"));
        var selected = new Reference(asset.id(), List.of(asset.units().getFirst().id()), Track.REFERENCE, Role.FACTS);
        var input = input("fixture", "生成说明文档", new Task(TaskType.DOCUMENT, List.of(selected), null));
        var consent = service.consent(actor, input);
        var authorized = input("fixture", "生成说明文档", new Task(TaskType.DOCUMENT, List.of(selected), consent.id()));
        assertThatCode(() -> service.requireConsent(actor, authorized)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireConsent(actor, input("other", "生成说明文档", authorized.sourceTask()))).isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> service.requireConsent(actor, input("fixture", "改成其他目标", authorized.sourceTask()))).isInstanceOf(ImagePromptException.class);
    }

    @Test void selectionBudgetRequiresExplicitSmallerRangeInsteadOfSilentTruncation() {
        var asset = service.upload(actor, file("notes.md", "a".repeat(130000)));
        var all = new Reference(asset.id(), asset.units().stream().map(Unit::id).toList(), Track.REFERENCE, Role.FACTS);
        assertThatThrownBy(() -> service.resolve(actor, new Task(TaskType.DOCUMENT, List.of(all), null))).isInstanceOf(ImagePromptException.class);
        var selected = new Reference(asset.id(), List.of(asset.units().getFirst().id()), Track.REFERENCE, Role.FACTS);
        assertThat(service.resolve(actor, new Task(TaskType.DOCUMENT, List.of(selected), null))).singleElement()
                .satisfies(source -> assertThat(source.units()).hasSize(1));
    }

    private MockMultipartFile file(String name, String content) { return new MockMultipartFile("file", name, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8)); }
    private Input input(String model, String goal, Task source) { return new Input(Mode.REFERENCE_RECONSTRUCTION, model, List.of(), goal, "", "", Language.ZH, "通用", OutputType.IMAGE, List.of(), List.of(), source); }
}
