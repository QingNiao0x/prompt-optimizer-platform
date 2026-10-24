package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 验证真实解码、像素预检与私有资产生命周期，文件扩展名和浏览器 MIME 不能作为准入依据。 */
class ImagePromptAssetServiceTest {
    @TempDir Path directory;
    private ImagePromptAssetService assets;
    private final ActorIdentity owner = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    @BeforeEach void setup() {
        assets = new ImagePromptAssetService(new ImagePromptStateStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules(), "MEMORY"), directory, Clock.systemUTC());
    }
    @AfterEach void close() { assets.close(); }
    @Test void acceptsRealPixelsEvenWithMisleadingNameAndRejectsFakeImages() throws Exception {
        var asset = assets.upload(owner, new MockMultipartFile("file", "/", "text/plain", png(4, 3)));
        assertThat(asset.name()).isEqualTo("参考图片");
        assertThat(asset.mimeType()).isEqualTo("image/png");
        assertThat(asset.width()).isEqualTo(4);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(assets.bytes(owner, asset.id()))).getHeight()).isEqualTo(3);
        assertThatThrownBy(() -> assets.upload(owner, new MockMultipartFile("file", "fake.png", "image/png", "not pixels".getBytes())))
                .isInstanceOf(ImagePromptException.class);
    }
    @Test void rejectsOversizedPixelHeadersBeforeAllocatingFullImage() throws Exception {
        assertThatThrownBy(() -> assets.upload(owner, new MockMultipartFile("file", "wide.png", "image/png", png(8193, 1))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("8192");
    }
    @Test void ownerAndRevocationAreEnforcedBeforeReadingBytes() throws Exception {
        var asset = assets.upload(owner, new MockMultipartFile("file", "fixture.png", "image/png", png(4, 3)));
        var other = new ActorIdentity(UUID.randomUUID(), owner.tenantId(), owner.workspaceId(), "other@example.test", "other");
        assertThatThrownBy(() -> assets.bytes(other, asset.id())).isInstanceOf(ImagePromptException.class);
        assets.revoke(other, asset.id());
        assertThat(assets.bytes(owner, asset.id())).isNotEmpty();
        assets.revoke(owner, asset.id());
        assertThatThrownBy(() -> assets.bytes(owner, asset.id())).isInstanceOf(ImagePromptException.class);
        assertThat(directory.resolve(asset.id() + ".image")).doesNotExist();
    }
    @Test void expiredAssetsCannotBeReadEvenWhenTheirMetadataRemainsInRedis() throws Exception {
        assets.close();
        var instant = new AtomicReference<>(Instant.now());
        var clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return instant.get(); }
        };
        assets = new ImagePromptAssetService(new ImagePromptStateStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules(), "MEMORY"), directory, clock);
        var asset = assets.upload(owner, new MockMultipartFile("file", "fixture.png", "image/png", png(2, 3)));
        instant.set(instant.get().plus(Duration.ofHours(2)));
        assertThatThrownBy(() -> assets.bytes(owner, asset.id())).isInstanceOf(ImagePromptException.class);
    }
    @Test void jpegExifRotationIsAppliedToPixelsAndOriginalExifIsRemoved() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        byte[] jpeg = output.toByteArray();
        // 有界 TIFF 小端 Orientation=6；规范化后尺寸换向且不再携带 APP1 设备/位置元数据。
        byte[] app1 = new byte[]{(byte)255,(byte)225,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        var combined = new ByteArrayOutputStream(); combined.write(jpeg,0,2); combined.write(app1); combined.write(jpeg,2,jpeg.length-2);
        var asset = assets.upload(owner, new MockMultipartFile("file", "rotated.jpg", "image/jpeg", combined.toByteArray()));
        assertThat(asset.width()).isEqualTo(3); assertThat(asset.height()).isEqualTo(2);
        assertThat(ImagePromptAssetService.orientation(assets.bytes(owner,asset.id()))).isEqualTo(1);
    }
    private byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }
}
