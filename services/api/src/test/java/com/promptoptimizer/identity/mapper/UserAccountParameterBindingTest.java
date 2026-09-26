package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.common.persistence.JsonbObjectTypeHandler;
import com.promptoptimizer.common.persistence.PostgresUuidTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 确认账户更新按原始字符串绑定，而不是把参数序列化成 JSON 文本。 */
class UserAccountParameterBindingTest {

    @Test
    void bindsPasswordHashAsPlainStringWhenJsonHandlerIsRegistered() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(JsonbObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        ClassPathResource resource = new ClassPathResource("mapper/identity/UserAccountMapper.xml");
        try (var input = resource.getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource.getPath(), configuration.getSqlFragments()).parse();
        }
        MappedStatement statement = configuration.getMappedStatement(
                UserAccountMapper.class.getName() + ".updatePasswordHash");
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Map<String, Object> parameters = Map.of("id", id, "passwordHash", "hash-value");
        BoundSql boundSql = statement.getBoundSql(parameters);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        ParameterHandler parameterHandler = configuration.newParameterHandler(statement, parameters, boundSql);

        parameterHandler.setParameters(preparedStatement);

        verify(preparedStatement).setString(1, "hash-value");
    }
}
