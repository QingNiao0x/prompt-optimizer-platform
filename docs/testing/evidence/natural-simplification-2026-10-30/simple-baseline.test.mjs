import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { generationRequest, executionRequest, parseBody, countAttempts, reserve } from './simple-baseline.mjs';

const original = { model: 'deepseek-flash', temperature: .2, messages: [
  { role: 'system', content: '共同执行规则' }, { role: 'user', content: '原始需求' }, { role: 'user', content: '完整材料与条件' }] };

test('generation retains raw input and complete materials without mutating source', () => {
  const result = generationRequest(original, { model: 'deepseek-flash', max_tokens: 32768 });
  assert.deepEqual(result.messages.slice(1), original.messages.slice(1));
  assert.equal(result.max_tokens, 32768);
  result.messages[1].content = '改变副本';
  assert.equal(original.messages[1].content, '原始需求');
});
test('execution changes only the task prompt', () => {
  const result = executionRequest(original, '优化正文');
  assert.deepEqual(result, { ...original, messages: [original.messages[0], { role: 'user', content: '优化正文' }, original.messages[2]] });
});
test('invalid or incomplete generation never falls back to original input', () => {
  assert.throws(() => parseBody({ choices: [{ finish_reason: 'length' }] }));
  assert.throws(() => parseBody({ choices: [{ finish_reason: 'stop', message: { content: '{"optimizedPrompt":""}' } }] }));
  assert.throws(() => parseBody({ choices: [{ finish_reason: 'stop', message: { content: '{"optimizedPrompt":"a","extra":1}' } }] }));
  assert.equal(parseBody({ choices: [{ finish_reason: 'stop', message: { content: '{"optimizedPrompt":"正文"}' } }] }), '正文');
});
test('reservation counts failed attempts, forbids repeats, closure and exhausted total', async () => {
  const root = await mkdtemp(resolve(tmpdir(), 'natural-budget-'));
  try {
    const old = resolve(root, 'old', 'traces');
    const stage = resolve(root, 'simple');
    await mkdir(old, { recursive: true });
    await writeFile(resolve(old, '001.json'), '{"state":"FAILED"}');
    const job = { id: 'G1', phase: 'GENERATION', caseId: 'N1', request: original };
    await reserve(root, stage, job, 3);
    assert.equal(await countAttempts(root), 2);
    await assert.rejects(() => reserve(root, stage, job, 3), /JOB_ALREADY_RESERVED/);
    await reserve(root, stage, { ...job, id: 'G2' }, 3);
    await assert.rejects(() => reserve(root, stage, { ...job, id: 'G3' }, 3), /BUDGET_EXHAUSTED/);
    await writeFile(resolve(stage, 'closure.json'), '{}');
    await assert.rejects(() => reserve(root, stage, { ...job, id: 'G4' }, 10), /CLOSED/);
  } finally { await rm(root, { recursive: true }); }
});
