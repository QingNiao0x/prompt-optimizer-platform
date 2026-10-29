/** 旧模块拥有独立业务规则，不是本次指定的新增和导入入口。 */
export const createLegacySurvey = (record) => ({ legacy: true, record });
