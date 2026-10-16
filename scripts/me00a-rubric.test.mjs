import test from 'node:test';
import assert from 'node:assert/strict';
import { REVIEW_RUBRIC, rubricInstruction, combineHardGates, scoreDisagreements } from './me00a-rubric.mjs';

test('all four dimensions have five immutable operational anchors', () => {
  assert.equal(Object.keys(REVIEW_RUBRIC.dimensions).length, 4);
  for (const anchors of Object.values(REVIEW_RUBRIC.dimensions)) {
    assert.equal(anchors.length, 5);
    assert.ok(Object.isFrozen(anchors));
  }
  assert.match(rubricInstruction(), /不代表用户研究已选择/);
  assert.match(rubricInstruction(), /重复生成不等于新增独立题/);
});

test('hard failures block and incomplete coverage stays open', () => {
  assert.equal(combineHardGates({ length: 'FAIL', format: 'PASS', security: 'OPEN' }), 'FAIL');
  assert.equal(combineHardGates({ length: 'PASS', security: 'OPEN' }), 'OPEN');
  assert.equal(combineHardGates({}), 'OPEN');
  assert.equal(combineHardGates({ length: 'PASS', security: 'PASS' }), 'PASS');
  assert.throws(() => combineHardGates({ length: true }));
});

test('score gaps require adjudication without replacing original ratings', () => {
  const first = { A: { accuracy: 5, completeness: 4, clarity: 4, executability: 4 } };
  const second = { A: { accuracy: 3, completeness: 3, clarity: 4, executability: 4 } };
  assert.deepEqual(scoreDisagreements(first, second), [
    { work: 'A', dimension: 'accuracy', first: 5, second: 3, state: 'ADJUDICATION_REQUIRED' },
  ]);
  assert.equal(first.A.accuracy, 5);
  assert.throws(() => scoreDisagreements(first, { B: second.A }));
  assert.throws(() => scoreDisagreements(first, { A: { ...second.A, accuracy: '3' } }));
});
