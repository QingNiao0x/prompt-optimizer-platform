package com.promptoptimizer.imageprompt.service;

import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.mapper.ImagePromptMapper;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 能力必须显式登记；同一模型仍可用于无图创作，不凭名称猜测视觉准入。 */
class ImagePromptModelServiceTest {
    private final PlatformModelCatalog catalog = mock(PlatformModelCatalog.class);
    private final ImagePromptMapper mapper = mock(ImagePromptMapper.class);
    private final ImagePromptHistoryService history = mock(ImagePromptHistoryService.class);
    private ImagePromptModelService service;
    @BeforeEach void setup() {
        var beans = new StaticListableBeanFactory(); beans.addBean("mapper",mapper);
        service = new ImagePromptModelService(catalog,beans.getBeanProvider(ImagePromptMapper.class),history);
        when(history.persistent()).thenReturn(true);
        var model = new PlatformModelCatalog.ModelEntry(UUID.randomUUID(),"fixture/vision-name","fixture","vision-name","视觉名字不等于能力",true,true,0);
        when(catalog.available()).thenReturn(List.of(model)); when(catalog.all()).thenReturn(List.of(model));
        when(catalog.resolve(model.publicId())).thenReturn(model);
    }
    @Test void undeclaredVisionCannotReceiveImagesButTextPlanStillWorks() {
        when(mapper.capabilities()).thenReturn(List.of());
        assertThat(service.require("fixture/vision-name",0).vision()).isFalse();
        assertThatThrownBy(() -> service.require("fixture/vision-name",1)).isInstanceOf(ImagePromptException.class);
    }
    @Test void declaredCapacityLimitsImagesAndDisabledCapabilityRetainsEvidence() {
        when(mapper.capabilities()).thenReturn(List.of(new VisionCapability("fixture/vision-name",true,1,"official fixture")));
        assertThat(service.require("fixture/vision-name",1).vision()).isTrue();
        assertThatThrownBy(() -> service.require("fixture/vision-name",2)).isInstanceOf(ImagePromptException.class);
        when(mapper.capabilities()).thenReturn(List.of(new VisionCapability("fixture/vision-name",false,1,"official fixture")));
        assertThat(service.models().getFirst().evidence()).isEqualTo("official fixture");
        assertThatThrownBy(() -> service.require("fixture/vision-name",1)).isInstanceOf(ImagePromptException.class);
    }

    @Test void userModelsKeepPublishedTextModelsAndCatalogOrderWhileAdminAlsoSeesDisabledModels() {
        var text = new PlatformModelCatalog.ModelEntry(UUID.randomUUID(),"fixture/text","fixture","text","文字模型",true,true,0);
        var vision = new PlatformModelCatalog.ModelEntry(UUID.randomUUID(),"fixture/vision","fixture","vision","视觉模型",true,false,1);
        var disabled = new PlatformModelCatalog.ModelEntry(UUID.randomUUID(),"fixture/off","fixture","off","已停用模型",false,false,2);
        when(catalog.available()).thenReturn(List.of(text,vision));
        when(catalog.all()).thenReturn(List.of(text,vision,disabled));
        when(mapper.capabilities()).thenReturn(List.of(new VisionCapability(vision.publicId(),true,1,"official fixture"),
                new VisionCapability(disabled.publicId(),true,2,"disabled fixture")));
        assertThat(service.models()).extracting(Model::id).containsExactly(text.publicId(),vision.publicId());
        assertThat(service.models()).extracting(Model::displayName).containsExactly("文字模型","视觉模型");
        assertThat(service.models().getFirst().vision()).isFalse();
        assertThat(service.adminModels()).extracting(Model::id).containsExactly(text.publicId(),vision.publicId(),disabled.publicId());
        assertThat(service.adminModels().getLast().evidence()).isEqualTo("disabled fixture");
    }

    @Test void configuringDisabledModelOnlyChangesCapabilityAndDoesNotPublishIt() {
        var disabled = new PlatformModelCatalog.ModelEntry(UUID.randomUUID(),"fixture/off","fixture","off","已停用模型",false,false,2);
        when(catalog.all()).thenReturn(List.of(disabled)); when(catalog.available()).thenReturn(List.of());
        service.configure(disabled.publicId(),true,1,"controlled fixture");
        verify(mapper).setCapability(new VisionCapability(disabled.publicId(),true,1,"controlled fixture"));
        assertThat(service.models()).isEmpty();
        verify(catalog,never()).create(any()); verify(catalog,never()).update(any(),any());
    }
}
