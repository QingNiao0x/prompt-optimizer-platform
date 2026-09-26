package com.promptoptimizer.common.persistence;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 PostgreSQL UUID 的写入、读取及非法驱动值处理。 */
class PostgresUuidTypeHandlerTest {

    private final PostgresUuidTypeHandler handler = new PostgresUuidTypeHandler();

    @Test
    void writesUuidAsNativeObject() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");

        handler.setNonNullParameter(statement, 1, id, null);

        verify(statement).setObject(1, id);
    }

    @Test
    void readsNativeUuidAndStringValues() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(resultSet.getObject("native_id")).thenReturn(id);
        when(resultSet.getObject("string_id")).thenReturn(id.toString());

        assertThat(handler.getNullableResult(resultSet, "native_id")).isEqualTo(id);
        assertThat(handler.getNullableResult(resultSet, "string_id")).isEqualTo(id);
    }

    @Test
    void rejectsMalformedUuidResultWithSafeDatabaseError() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("id")).thenReturn("malformed");

        assertThatThrownBy(() -> handler.getNullableResult(resultSet, "id"))
                .isInstanceOf(SQLException.class)
                .hasMessage("数据库 UUID 字段格式无效")
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo("22000");
    }
}
