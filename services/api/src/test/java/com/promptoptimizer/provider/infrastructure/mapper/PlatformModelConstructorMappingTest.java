package com.promptoptimizer.provider.infrastructure.mapper;

import com.promptoptimizer.common.persistence.PostgresUuidTypeHandler;
import com.promptoptimizer.provider.mapper.PlatformModelMapper;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 确认模型目录记录使用原始类型构造，避免包装类型导致 NoSuchMethodException。 */
class PlatformModelConstructorMappingTest {

    @Test
    void modelEntryConstructorUsesPrimitiveArguments() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        ClassPathResource resource = new ClassPathResource("mapper/provider/PlatformModelMapper.xml");
        try (var input = resource.getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource.getPath(), configuration.getSqlFragments()).parse();
        }
        ResultMap resultMap = configuration.getResultMap(
                PlatformModelMapper.class.getName() + ".modelEntry");
        List<Class<?>> argumentTypes = resultMap.getConstructorResultMappings().stream()
                .map(ResultMapping::getJavaType)
                .toList();
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        List<Object> arguments = List.of(id, "public-id", "tokenhub", "glm", "GLM", true, false, 1);

        ModelEntry entry = new DefaultObjectFactory().create(ModelEntry.class, argumentTypes, arguments);

        assertThat(argumentTypes).containsExactly(
                UUID.class, String.class, String.class, String.class, String.class,
                boolean.class, boolean.class, int.class);
        assertThat(entry.id()).isEqualTo(id);
        assertThat(entry.enabled()).isTrue();
        assertThat(entry.defaultModel()).isFalse();
        assertThat(entry.sortOrder()).isEqualTo(1);
    }
}
