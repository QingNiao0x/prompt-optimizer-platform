import assert from 'node:assert/strict';
import { anonymousWork, randomOrder, repeatFromFrozenJob } from './support.mjs';
const text = '完整作品\r\n保持原样。';
const result = { status: 200, version: '51', id: 'LOCAL_ONLY', body: { data: { content: text, finishReason: 'stop', usage: { totalTokens: 1 } } } };
assert.deepEqual(anonymousWork(result, 'A'), { label: 'A', deliveryState: 'DELIVERED', content: text, failure: null });
const failed = anonymousWork({ status: 502, id: 'LOCAL_ONLY', body: { error: { code: 'LOCAL_FAILURE', message: 'LOCAL provenance hidden' } } }, 'B');
assert.deepEqual(failed, { label: 'B', deliveryState: 'NOT_DELIVERED', content: null, failure: { httpStatus: 502, code: 'LOCAL_FAILURE' } });
assert.equal(anonymousWork({ ...result, body: { data: { content: text, finishReason: 'length' } } }, 'C').content, text);
assert.equal(anonymousWork({ ...result, body: { data: { content: text, finishReason: 'length' } } }, 'C').deliveryState, 'INCOMPLETE');
assert.equal(anonymousWork({ ...result, body: { data: { content: '  ', finishReason: 'stop' } } }, 'D').deliveryState, 'EMPTY');
assert.ok(!JSON.stringify(anonymousWork(result, 'A')).includes('LOCAL_ONLY'));
const entries = ['v31r1', 'v31r2', 'v51r1', 'v51r2', 'rawr1', 'rawr2'].map(key => ({ key })), seed = 'a'.repeat(64);
assert.deepEqual(randomOrder(entries, seed, 'LOCAL_ONLY'), randomOrder(entries, seed, 'LOCAL_ONLY'));
assert.deepEqual(entries.map(item => item.key), ['v31r1', 'v31r2', 'v51r1', 'v51r2', 'rawr1', 'rawr2']);
assert.equal(repeatFromFrozenJob({ id: 'LOCAL_ONLY_EXEC_R1' }), 1);
assert.equal(repeatFromFrozenJob({ id: 'LOCAL_ONLY_EXEC_R2', repeat: 2 }), 2);
for (const job of [{ id: 'LOCAL_ONLY_EXEC_R3' }, { id: 'LOCAL_ONLY_EXEC_R1', repeat: 2 },
  { id: 'LOCAL_ONLY_EXEC_R1', repeat: '1' }, { id: 'LOCAL_ONLY_EXEC_R1', repeat: null }]) assert.throws(() => repeatFromFrozenJob(job));
console.log(JSON.stringify({ syntheticOnly: true, fullTextPreserved: true, failuresAndTruncationPreserved: true,
  hiddenMetadataWhitelist: true, deterministicPrivateSeed: true, repeatIdPositiveChecks: 2, repeatConflictRejections: 4,
  actualHttpRequests: 0, actualModelCalls: 0, realResultsMaterialized: false }));
