package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.service.impl.ContextPageRasterizer;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.ImageRole;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证真实图片/幻灯片/视频解析、单位来源与掩码字节隔离；不访问模型供应商。
 * @author QingNiao
 * @since 0.1.0
 */
class ImagePromptMediaServiceTest {
    @TempDir Path directory;
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private ImagePromptMediaService media;

    @BeforeEach void setup() {
        media = create(new ImagePromptMediaDecoder(json, "missing-ffmpeg-fixture", "missing-ffprobe-fixture"), Clock.systemUTC());
    }
    @AfterEach void close() { media.close(); }

    @Test void realImagePixelsAreNormalizedAndMaskedBeforeResolving() throws Exception {
        MediaAsset uploaded = media.upload(actor, new MockMultipartFile("file", "misleading.bin", "text/plain", png()), null, null);
        MediaAsset ready = await(uploaded.id()); Unit unit = ready.units().getFirst();
        assertThat(ready.kind()).isEqualTo(Kind.IMAGE); assertThat(ready.status()).isEqualTo(Status.READY);
        var ref = new MaterialRef(ready.id(), Track.REPLACEMENT, ImageRole.SUBJECT,
                List.of(new UnitSelection(unit.id(), List.of(new Mask(0, 0, 0.5, 1)))));
        var resolved = media.resolve(actor, List.of(ref));
        BufferedImage outbound = ImageIO.read(new ByteArrayInputStream(resolved.images().getFirst().bytes()));
        assertThat(outbound.getRGB(1, 1) & 0xffffff).isZero();
        assertThat(outbound.getRGB(15, 1) & 0xffffff).isEqualTo(0xffffff);
        assertThat(resolved.sources().getFirst().track()).isEqualTo(Track.REPLACEMENT);
        assertThat(resolved.sources().getFirst().units().getFirst().masks()).hasSize(1);
        assertThat(media.export(actor, ready.id(), unit.id(), List.of(new Mask(0, 0, 0.5, 1)))).isEqualTo(resolved.images().getFirst().bytes());
        assertThat(Files.exists(directory.resolve(uploaded.id().toString()).resolve("source.bin"))).isFalse();
    }

    @Test void presentationRetainsPageAnchorsButDoesNotExposeNotesHiddenSlidesOrMaskedText() throws Exception {
        MediaAsset ready = await(media.upload(actor, pptx(false), null, null).id());
        assertThat(ready.kind()).isEqualTo(Kind.PPTX); assertThat(ready.totalUnits()).isEqualTo(2);
        assertThat(ready.units()).extracting(Unit::page).containsExactly(1, 3);
        assertThat(ready.units().getFirst().text()).contains("Public title", "位置/尺寸").doesNotContain("Hidden slide", "Private speaker notes");
        Unit selected = ready.units().getLast();
        assertThat(ImageIO.read(new ByteArrayInputStream(media.preview(actor, ready.id(), selected.id())))).isNotNull();
        Resolved resolved = media.resolve(actor, List.of(new MaterialRef(ready.id(), Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new UnitSelection(selected.id(), List.of(new Mask(0, 0, 1, 1)))))));
        assertThat(resolved.images()).hasSize(1);
        assertThat(resolved.sources().getFirst().units()).hasSize(1);
        assertThat(resolved.sources().getFirst().units().getFirst().page()).isEqualTo(3);
        assertThat(resolved.sources().getFirst().units().getFirst().text()).isEmpty();
        assertThat(resolved.warnings()).anyMatch(value -> value.contains("1 / 2"));
        assertThat(resolved.images().getFirst().source().name()).contains("第 3 页");
    }

    @Test void knownSecretsInPptTextAreBlockedUnlessActualPixelsAreMasked() throws Exception {
        MediaAsset ready = await(media.upload(actor, pptx(true), null, null).id()); Unit selected = ready.units().getFirst();
        assertThatThrownBy(() -> media.resolve(actor, List.of(new MaterialRef(ready.id(), Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new UnitSelection(selected.id(), List.of()))))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("凭据");
        Resolved masked = media.resolve(actor, List.of(new MaterialRef(ready.id(), Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new UnitSelection(selected.id(), List.of(new Mask(0, 0, 1, 1)))))));
        assertThat(masked.sources().getFirst().units().getFirst().text()).isEmpty();
    }

    @Test void ordinaryChartsWithEmbeddedXlsxAreAcceptedAndCacheValuesAreSourceFacts() throws Exception {
        byte[] bytes;
        try (var ppt = new XMLSlideShow(); var output = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(720, 405)); var slide = ppt.createSlide(); var chart = ppt.createChart();
            var categories = chart.createCategoryAxis(AxisPosition.BOTTOM); var values = chart.createValueAxis(AxisPosition.LEFT);
            var data = chart.createData(ChartTypes.BAR, categories, values);
            data.addSeries(XDDFDataSourcesFactory.fromArray(new String[]{"Alpha", "Beta"}), XDDFDataSourcesFactory.fromArray(new Double[]{12.5, 38.0}));
            chart.plot(data); slide.addChart(chart, new java.awt.Rectangle(40, 40, 600, 300));
            try (var workbook = new XSSFWorkbook()) { workbook.createSheet("Data").createRow(0).createCell(0).setCellValue("Not executed"); chart.saveWorkbook(workbook); }
            ppt.write(output); bytes = output.toByteArray();
        }
        boolean workbookPresent = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) if (entry.getName().startsWith("ppt/embeddings/") && entry.getName().endsWith(".xlsx")) workbookPresent = true;
        }
        assertThat(workbookPresent).isTrue();
        MediaAsset ready = await(media.upload(actor, new MockMultipartFile("file", "chart.pptx", "application/octet-stream", bytes), null, null).id());
        assertThat(ready.units().getFirst().text()).contains("图表缓存", "Alpha = 12.5", "Beta = 38.0");
        assertThat(media.preview(actor, ready.id(), ready.units().getFirst().id())).isNotEmpty();
    }

    @Test void assetUnitAndIdentityScopesAreCheckedBeforeReadingPrivatePixels() throws Exception {
        MediaAsset first = await(media.upload(actor, new MockMultipartFile("file", "fixture.png", "image/png", png()), null, null).id());
        MediaAsset second = await(media.upload(actor, new MockMultipartFile("file", "other.png", "image/png", png()), null, null).id());
        var anotherWorkspace = new ActorIdentity(actor.userId(), actor.tenantId(), UUID.randomUUID(), "fixture@example.test", "fixture");
        var anotherTenant = new ActorIdentity(actor.userId(), UUID.randomUUID(), actor.workspaceId(), "fixture@example.test", "fixture");
        var anotherUser = new ActorIdentity(UUID.randomUUID(), actor.tenantId(), actor.workspaceId(), "other@example.test", "other");
        for (ActorIdentity other : List.of(anotherWorkspace, anotherTenant, anotherUser)) {
            assertThatThrownBy(() -> media.preview(other, first.id(), first.units().getFirst().id())).isInstanceOf(ImagePromptException.class);
            assertThatThrownBy(() -> media.revoke(other, first.id())).isInstanceOf(ImagePromptException.class);
        }
        assertThatThrownBy(() -> media.resolve(actor, List.of(new MaterialRef(first.id(), Track.REFERENCE, ImageRole.STYLE,
                List.of(new UnitSelection(second.units().getFirst().id(), List.of()))))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("不属于");
        assertThat(media.preview(actor, first.id(), first.units().getFirst().id())).isNotEmpty();
        media.revoke(actor, first.id());
        assertThatThrownBy(() -> media.require(actor, first.id())).isInstanceOf(ImagePromptException.class);
    }

    @Test void invalidMasksAndDuplicateSelectionsAreRejectedInsteadOfSilentlyChangingSources() throws Exception {
        MediaAsset ready = await(media.upload(actor, new MockMultipartFile("file", "fixture.png", "image/png", png()), null, null).id()); Unit unit = ready.units().getFirst();
        assertThatThrownBy(() -> media.resolve(actor, List.of(new MaterialRef(ready.id(), Track.REFERENCE, ImageRole.STYLE,
                List.of(new UnitSelection(unit.id(), List.of(new Mask(Double.NaN, 0, 1, 1))))))))
                .isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> media.resolve(actor, List.of(new MaterialRef(ready.id(), Track.REFERENCE, ImageRole.STYLE,
                List.of(new UnitSelection(unit.id(), List.of()), new UnitSelection(unit.id(), List.of()))))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("重复");
    }

    @Test void fakeFilesAndExternalPptResourcesCannotBecomeReadyMaterials() throws Exception {
        assertThatThrownBy(() -> media.upload(actor, new MockMultipartFile("file", "fake.mp4", "video/mp4", "not a movie".getBytes()), null, null))
                .isInstanceOf(ImagePromptException.class);
        MockMultipartFile ppt = pptx(false); var bytes = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(ppt.getInputStream()); var output = new ZipOutputStream(bytes)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName())); output.write(input.readAllBytes()); output.closeEntry();
            }
            output.putNextEntry(new ZipEntry("ppt/_rels/external-fixture.xml.rels"));
            output.write("<Relationships><Relationship TargetMode=\"External\" Type=\"image\" Target=\"https://fixture.invalid/private.png\"/></Relationships>".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        MediaAsset asset = media.upload(actor, new MockMultipartFile("file", "external.pptx", "application/octet-stream", bytes.toByteArray()), null, null);
        MediaAsset failed = awaitFinished(asset.id());
        assertThat(failed.status()).isEqualTo(Status.FAILED); assertThat(failed.warnings()).anyMatch(value -> value.contains("外部链接"));
        assertThatThrownBy(() -> media.preview(actor, asset.id(), UUID.randomUUID())).isInstanceOf(ImagePromptException.class);
    }

    @Test void runningTaskAndCancellationCannotSubmitEmptyEvidence() throws Exception {
        media.close(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var decoder = new ImagePromptMediaDecoder(json, "missing", "missing") {
            @Override public Decoded decode(Path source, Kind kind, UUID id, Long start, Long end) throws Exception {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); return super.decode(source, kind, id, start, end);
            }
        };
        media = create(decoder, Clock.systemUTC());
        MediaAsset asset = media.upload(actor, new MockMultipartFile("file", "fixture.png", "image/png", png()), null, null);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> media.resolve(actor, List.of(new MaterialRef(asset.id(), Track.REFERENCE, ImageRole.STYLE,
                List.of(new UnitSelection(UUID.randomUUID(), List.of()))))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("仍在解析");
        media.revoke(actor, asset.id()); release.countDown();
        assertThatThrownBy(() -> media.require(actor, asset.id())).isInstanceOf(ImagePromptException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "mov", "webm"})
    void actualVideoDecoderProducesTimestampedFramesWithinSelectedSourceRange(String extension) throws Exception {
        String ffmpeg = System.getProperty("test.ffmpeg"), ffprobe = System.getProperty("test.ffprobe");
        Assumptions.assumeTrue(ffmpeg != null && ffprobe != null, "设置 test.ffmpeg/test.ffprobe 以执行本机真实解码验证");
        Path video = directory.resolve("synthetic." + extension);
        Process generation = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=4:duration=3",
                "-c:v", extension.equals("webm") ? "libvpx" : "libx264", "-pix_fmt", "yuv420p", "-y", video.toString()).redirectErrorStream(true).start();
        assertThat(generation.waitFor(15, TimeUnit.SECONDS)).isTrue(); assertThat(generation.exitValue()).isZero();
        media.close(); media = create(new ImagePromptMediaDecoder(json, ffmpeg, ffprobe), Clock.systemUTC());
        assertThat(media.capabilities().videoAvailable()).isTrue();
        MediaAsset ready = await(media.upload(actor, new MockMultipartFile("file", "clip." + extension, "video/" + extension, Files.readAllBytes(video)), 1000L, 2500L).id());
        assertThat(ready.kind()).isEqualTo(Kind.VIDEO); assertThat(ready.durationMs()).isBetween(2900L, 3100L);
        assertThat(ready.units()).isNotEmpty().hasSizeLessThanOrEqualTo(12);
        assertThat(ready.units()).allSatisfy(unit -> {
            assertThat(unit.timestampMs()).isBetween(1000L, 2500L); assertThat(unit.page()).isNull();
            assertThat(unit.width()).isLessThanOrEqualTo(1600);
        });
        assertThat(ready.warnings()).anyMatch(value -> value.contains("音频未发送"));
        assertThat(media.preview(actor, ready.id(), ready.units().getFirst().id())).isNotEmpty();
        // 仅选首帧：来源说明必须区分本地已生成预览总数与真正参与本任务的单位数，不能丢掉原片段风险。
        Resolved selected = media.resolve(actor, List.of(new MaterialRef(ready.id(), Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new UnitSelection(ready.units().getFirst().id(), List.of())))));
        assertThat(selected.images()).hasSize(1);
        assertThat(selected.sources().getFirst().units()).hasSize(1);
        assertThat(selected.warnings()).contains("解析共生成 " + ready.units().size() + " 个预览帧；本任务选择 1 个采样帧（具体时间戳见 units）。");
        assertThat(selected.warnings()).anyMatch(value -> value.contains("1000–2500 ms") && value.contains("音频未发送"));
        assertThat(selected.warnings()).anyMatch(value -> value.contains("不能据此声称完整运动"));
    }

    private ImagePromptMediaService create(ImagePromptMediaDecoder decoder, Clock clock) {
        return new ImagePromptMediaService(new ImagePromptStateStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), json, "MEMORY"),
                new ContextPageRasterizer(), decoder, directory, clock);
    }
    private MediaAsset await(UUID id) throws Exception {
        MediaAsset result = awaitFinished(id); assertThat(result.status()).withFailMessage("素材解析未成功：%s", result.warnings()).isIn(Status.READY, Status.PARTIAL); return result;
    }
    private MediaAsset awaitFinished(UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); MediaAsset asset;
        do { asset = media.require(actor, id); if (asset.status() != Status.PROCESSING) return asset; Thread.sleep(20); } while (System.nanoTime() < deadline);
        throw new AssertionError("素材任务未及时完成");
    }
    private byte[] png() throws Exception {
        var image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 20, 10); graphics.dispose();
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); image.flush(); return output.toByteArray();
    }
    private MockMultipartFile pptx(boolean credential) throws Exception {
        try (var ppt = new XMLSlideShow(); var output = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(720, 405));
            var first = ppt.createSlide(); var title = first.createTextBox(); title.setAnchor(new java.awt.Rectangle(40, 40, 400, 80));
            title.setText(credential ? "api_key=sk-testFixtureCredential012345678901234567890" : "Public title");
            var notes = ppt.getNotesSlide(first); notes.createTextBox().setText("Private speaker notes");
            var hidden = ppt.createSlide(); hidden.getXmlObject().setShow(false); hidden.createTextBox().setText("Hidden slide");
            var last = ppt.createSlide(); var body = last.createTextBox(); body.setAnchor(new java.awt.Rectangle(60, 60, 300, 80)); body.setText("Replacement content");
            ppt.write(output); return new MockMultipartFile("file", "fixture.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", output.toByteArray());
        }
    }
}
