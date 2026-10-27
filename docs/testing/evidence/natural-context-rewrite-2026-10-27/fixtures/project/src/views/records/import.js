import { validateSurveyDate } from './dateRules.js';

/** 导入保持按行部分成功；无效日期只影响所属行，不改变其他记录。 */
export function importSurveys(rows, today) {
  const inserted = [];
  const errors = [];
  rows.forEach((record, index) => {
    const code = validateSurveyDate(record.surveyDate, today);
    if (code) errors.push({ row: index + 1, field: 'surveyDate', code });
    else inserted.push({ ...record });
  });
  return { inserted, errors };
}
