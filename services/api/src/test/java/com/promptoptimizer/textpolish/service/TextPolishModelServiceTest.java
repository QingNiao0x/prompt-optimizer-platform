package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.mapper.TextPolishMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 润色默认与全局默认独立，管理员配置不能绕过目录启停和逻辑删除。 */
class TextPolishModelServiceTest {
    @Test @SuppressWarnings("unchecked") void moduleDefaultsAndDisabledModelsAreEnforcedWithoutChangingCatalog() {
        var catalog = mock(PlatformModelCatalog.class); var mapper = mock(TextPolishMapper.class);
        ObjectProvider<TextPolishMapper> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(mapper);
        var a = new ModelEntry(UUID.randomUUID(),"a","route","a","A",true,true,0);
        var b = new ModelEntry(UUID.randomUUID(),"b","route","b","B",true,false,1);
        when(catalog.available()).thenReturn(List.of(a,b)); when(catalog.all()).thenReturn(List.of(a,b)); when(catalog.resolve("b")).thenReturn(b);
        when(mapper.policy()).thenReturn("{\"enabledModelIds\":[\"b\"],\"defaultModelId\":\"b\"}"); when(mapper.setPolicy(any(),any())).thenReturn(1);
        var service = new TextPolishModelService(catalog,provider,new ObjectMapper(),true);
        assertThat(service.require("")).isEqualTo(b); assertThatThrownBy(() -> service.require("a")).isInstanceOf(TextPolishException.class);
        service.configure(new ModelPolicy(List.of("b"),"b")); verify(catalog,never()).update(any(),any());
        when(catalog.available()).thenReturn(List.of(a));
        assertThat(service.models()).isEmpty(); assertThatThrownBy(() -> service.require("b")).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> service.configure(new ModelPolicy(List.of("missing"),"missing"))).isInstanceOf(TextPolishException.class);
    }
}
