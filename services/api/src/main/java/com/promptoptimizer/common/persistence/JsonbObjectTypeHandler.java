package com.promptoptimizer.common.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.io.IOException;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 在 PostgreSQL JSONB 与 Java Map/List 之间转换；数据库结构由调用方的 DTO 校验。
 *
 * <p>不能注册为 {@code Object} 的全局处理器。MyBatis 在解析未声明 javaType 的
 * {@code #{}} 时会按 Object 选处理器，若选中本类，会把登录名等普通字符串序列化成带引号的 JSON，
 * 导致身份查询匹配不到已有记录。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@MappedTypes({ Map.class, List.class })
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbObjectTypeHandler extends BaseTypeHandler<Object> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Object parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            statement.setString(index, OBJECT_MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法序列化持久化 JSON 值", "22000", exception);
        }
    }

    @Override
    public Object getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public Object getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public Object getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    private Object parse(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (IOException exception) {
            throw new SQLException("数据库中的 JSON 值无法解析", "22000", exception);
        }
    }
}
