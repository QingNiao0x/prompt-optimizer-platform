import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSurvey } from './create.js';
import { importSurveys } from './import.js';

test('新增保留有效日历日期和其他字段', () => {
  const input = { id: 'synthetic-a', surveyDate: '2026-10-26', category: 'sample' };
  assert.deepEqual(createSurvey(input, '2026-10-27'), { ok: true, record: input });
});

test('导入按行返回无效日历日期，保留其他有效行', () => {
  const first = { id: 'synthetic-a', surveyDate: '2026-10-26' };
  assert.deepEqual(importSurveys([first, { surveyDate: '2026-02-30' }], '2026-10-27'), {
    inserted: [first], errors: [{ row: 2, field: 'surveyDate', code: 'INVALID_DATE' }],
  });
});
