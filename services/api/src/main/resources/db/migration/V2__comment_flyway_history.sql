-- 为 Flyway 自动创建的迁移元数据表补充表级和字段级注释。
-- 该表不是业务表，不参与提示词优化业务关系；它由 Flyway 负责维护。

COMMENT ON TABLE flyway_schema_history IS 'Flyway 迁移历史表：记录数据库迁移版本、脚本、校验和、执行耗时及执行结果';
COMMENT ON COLUMN flyway_schema_history.installed_rank IS '迁移执行顺序编号';
COMMENT ON COLUMN flyway_schema_history.version IS '迁移版本号，例如 V1、V2';
COMMENT ON COLUMN flyway_schema_history.description IS '迁移说明';
COMMENT ON COLUMN flyway_schema_history.type IS '迁移类型，例如 SQL';
COMMENT ON COLUMN flyway_schema_history.script IS '实际执行的迁移脚本名称';
COMMENT ON COLUMN flyway_schema_history.checksum IS '迁移脚本校验和，用于检测已执行脚本是否被修改';
COMMENT ON COLUMN flyway_schema_history.installed_by IS '执行迁移的数据库用户';
COMMENT ON COLUMN flyway_schema_history.installed_on IS '迁移执行时间';
COMMENT ON COLUMN flyway_schema_history.execution_time IS '迁移执行耗时，单位为毫秒';
COMMENT ON COLUMN flyway_schema_history.success IS '迁移是否执行成功';
