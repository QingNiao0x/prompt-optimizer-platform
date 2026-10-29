import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkH04Work } from './check-h04.mjs';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), stage = process.argv[2];
if (!/^[a-z0-9-]+$/.test(stage ?? '')) throw Error('需要冻结作品阶段名');
const jobs = JSON.parse(await readFile(resolve(evidence, stage, 'jobs.json'), 'utf8'));
const output = resolve(here, 'h04-results', stage); await mkdir(output, { recursive: true });
const records = [];
for (const job of jobs.filter(job => job.caseId === 'H04' && job.phase === 'EXECUTION')) {
  const result = JSON.parse(await readFile(resolve(evidence, stage, 'results', `${job.id}.json`), 'utf8'));
  const verdict = result.status === 200 && result.body?.data?.finishReason === 'stop'
    ? await checkH04Work(result.body.data.content)
    : { passed: false, formatPassed: null, behaviorUnavailable: true, resultStatus: result.status,
      finishReason: result.body?.data?.finishReason ?? null, reason: '正式作品未完整返回，保留原件，不补采成功作品' };
  const checks = new Map((verdict.behavior?.checks ?? []).map(check => [check.id, check.passed]));
  const behaviorItemFacts = {
    1: verdict.behavior ? !verdict.behavior.artifactLoadFailed : null,
    2: checks.get('normal_merge') ?? null, 3: checks.get('key_and_display_name') ?? null,
    4: checks.get('first_seen_order') ?? null, 5: checks.get('empty_fresh_array') ?? null,
    6: checks.get('invalid_parameter') ?? null,
    7: checks.has('invalid_item_and_name') && checks.has('invalid_quantity')
      ? checks.get('invalid_item_and_name') && checks.get('invalid_quantity') : null,
    8: checks.get('safe_integer_and_overflow') ?? null,
    9: checks.has('immutability_and_shape') && checks.has('valid_object_and_string_keys')
      ? checks.get('immutability_and_shape') && checks.get('valid_object_and_string_keys') : null,
    10: { formatPassed: verdict.formatPassed, externalBehaviorSourceReview: '待协调者阅读实际代码核对，不用VM通过替代源码审阅。' },
  };
  const record = { id: job.id, version: job.version, ...verdict, behaviorItemFacts,
    originalSealedChecksPreserved: true, actualNewModelCalls: 0 };
  await writeFile(resolve(output, `${job.id}.json`), JSON.stringify(record, null, 2) + '\n', { flag: 'wx' });
  records.push({ id: job.id, passed: record.passed, formatPassed: record.formatPassed,
    behaviorPassed: record.behavior?.passed ?? null, sourceReviewPending: true });
}
await writeFile(resolve(output, 'summary.json'), JSON.stringify({ stage, records, actualModelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ stage, records, actualModelCalls: 0 }));
