package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.service.impl.ContextPageRasterizer;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.imageprompt.controller.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.ImageRole;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 不启动数据库的真实控制器合同验证：multipart、轮询、私有预览、导出 DTO 和撤销均连接实际素材服务。
 * CurrentActor 使用既有受控身份夹具；安全过滤链另由身份模块覆盖，不将 standalone MVC 冒充登录验收。
 * @author QingNiao
 * @since 0.1.0
 */
class ImagePromptMediaControllerTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    // 函数式端口可直接注入受控身份，避免本机沙箱的动态 attach 权限影响协议测试。
    private final AtomicReference<ActorIdentity> identity = new AtomicReference<>(actor);
    private final CurrentActor current = identity::get;
    private ImagePromptMediaService media;
    private MockMvc mvc;

    @BeforeEach void setup() {
        var states = new ImagePromptStateStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), json, "MEMORY");
        media = new ImagePromptMediaService(states, new ContextPageRasterizer(), new ImagePromptMediaDecoder(json, "missing-fixture", "missing-fixture"), directory, Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(new ImagePromptMediaController(media, current)).setControllerAdvice(new ImagePromptExceptionHandler()).build();
        identity.set(actor);
    }
    @AfterEach void close() { media.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"png", "pptx"})
    void multipartStatusPreviewExportAndRevocationUseSamePrivatePixelContract(String format) throws Exception {
        MockMultipartFile file = fixture(format);
        byte[] response = mvc.perform(multipart("/api/v1/image-prompt-media").file(file)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        UUID assetId = UUID.fromString(json.readTree(response).path("data").path("id").asText());
        var asset = await(assetId); UUID unitId = asset.units().getFirst().id();
        assertThat(asset.kind()).isEqualTo(format.equals("png") ? Kind.IMAGE : Kind.PPTX);
        byte[] preview = mvc.perform(get("/api/v1/image-prompt-media/{id}/units/{unitId}", assetId, unitId))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(preview))).isNotNull();

        byte[] exported = mvc.perform(post("/api/v1/image-prompt-media/{id}/units/{unitId}/export", assetId, unitId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"masks\":[{\"x\":0,\"y\":0,\"width\":1,\"height\":1}]}"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Content-Disposition", "attachment; filename=material-" + unitId + ".png"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage black = ImageIO.read(new ByteArrayInputStream(exported));
        for (int y = 0; y < black.getHeight(); y++) for (int x = 0; x < black.getWidth(); x++) assertThat(black.getRGB(x, y) & 0xffffff).isZero();
        var resolved = media.resolve(actor, List.of(new MaterialRef(assetId, Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new UnitSelection(unitId, List.of(new Mask(0, 0, 1, 1)))))));
        assertThat(exported).isEqualTo(resolved.images().getFirst().bytes());
        assertThat(resolved.sources().getFirst().units().getFirst().text()).isEmpty();

        // 同一用户改到其他工作区也不能通过 URL 获得素材，错误按生产图像异常信封返回。
        identity.set(new ActorIdentity(actor.userId(), actor.tenantId(), UUID.randomUUID(), actor.email(), actor.displayName()));
        mvc.perform(get("/api/v1/image-prompt-media/{id}", assetId)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IMAGE_PROMPT_EXPIRED"));
        identity.set(actor);
        mvc.perform(delete("/api/v1/image-prompt-media/{id}", assetId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/image-prompt-media/{id}/units/{unitId}", assetId, unitId)).andExpect(status().isConflict());
    }

    @Test void exportRejectsOutOfRangeMaskInsteadOfReturningUnmaskedPixels() throws Exception {
        MediaAsset asset = await(media.upload(actor, fixture("png"), null, null).id());
        mvc.perform(post("/api/v1/image-prompt-media/{id}/units/{unitId}/export", asset.id(), asset.units().getFirst().id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"masks\":[{\"x\":0.9,\"y\":0,\"width\":0.5,\"height\":1}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IMAGE_PROMPT_INVALID"));
    }

    private MediaAsset await(UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            byte[] response = mvc.perform(get("/api/v1/image-prompt-media/{id}", id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            MediaAsset asset = json.treeToValue(json.readTree(response).path("data"), MediaAsset.class);
            if (asset.status() == Status.READY || asset.status() == Status.PARTIAL) return asset;
            assertThat(asset.status()).withFailMessage("素材失败：%s", asset.warnings()).isEqualTo(Status.PROCESSING);
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("素材解析未及时完成");
    }

    private MockMultipartFile fixture(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        if (format.equals("png")) {
            var image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 32, 16); graphics.dispose(); ImageIO.write(image, "png", output); image.flush();
        } else try (var ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension(320, 180)); var slide = ppt.createSlide(); var text = slide.createTextBox();
            text.setAnchor(new Rectangle(10, 10, 250, 60)); text.setText("Visible fixture text");
            ppt.getNotesSlide(slide).createTextBox().setText("Private notes must not be sent"); ppt.write(output);
        }
        return new MockMultipartFile("file", "fixture." + format, "application/octet-stream", output.toByteArray());
    }
}
