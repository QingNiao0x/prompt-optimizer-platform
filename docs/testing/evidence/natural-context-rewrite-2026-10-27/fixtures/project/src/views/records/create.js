import { validateSurveyDate } from './dateRules.js';

/** 新增失败返回字段错误；成功时保留调用者给出的其他字段。 */
export function createSurvey(input, today) {
  const error = validateSurveyDate(input.surveyDate, today);
  return error ? { ok: false, fieldErrors: { surveyDate: error } }
    : { ok: true, record: { ...input } };
}
