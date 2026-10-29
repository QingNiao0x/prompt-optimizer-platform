/** 两个录入入口共用日历日期校验，错误代码用于表单和导入行提示。 */
export function validateSurveyDate(value, today) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return 'INVALID_DATE';
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== value) return 'INVALID_DATE';
  return null;
}
