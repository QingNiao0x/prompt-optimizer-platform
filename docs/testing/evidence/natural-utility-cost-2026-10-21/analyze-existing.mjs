import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

// 只重排历史合成原件的内部记录，保持正文、出处与全部锚点，字符节省不冒充实际Token节省。
const previous = resolve('docs/testing/evidence/natural-coverage-workflow-2026-10-20');
const evidence = resolve('docs/testing/evidence/natural-utility-cost-2026-10-21');
const protocol = JSON.parse(await readFile(resolve(previous, 'protocol-candidate2.json'), 'utf8'));
const traces = (await readFile(resolve(previous, 'transports.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
const rows = [];
for (const caseId of protocol.generation) {
  const jobId = protocol.currentJobs[caseId + '_V4_GEN'] ?? caseId + '_V4_GEN';
  const trace = traces.find(row => row.jobId === jobId);
  const choice = trace.response.choices[0];
  const content = choice.message.content.trim();
  let original, singleRootClosure = false;
  try { original = JSON.parse(content); }
  catch {
    assert.equal(choice.finish_reason, 'stop');
    assert(content.startsWith('{') && /[}\]]$/.test(content));
    original = JSON.parse(content + '}'); singleRootClosure = true;
  }
  const compact = structuredClone(original);
  const old = original.taskUnderstanding, task = compact.taskUnderstanding;
  for (const [field, coverage] of [['requirements', 'requirementCoverage'], ['additions', 'additionCoverage']]) {
    assert.equal(old[field].length, old.review[coverage].length);
    for (let index = 0; index < task[field].length; index++) {
      const entry = task[field][index];
      const anchors = old.review[coverage].filter(row => row.index === index);
      assert.equal(anchors.length, 1);
      entry.quotes = anchors[0].quotes;
      delete entry.text;
      if (field === 'requirements') {
        assert.deepEqual(entry.evidence, old[field][index].evidence);
        assert.deepEqual(entry.dimensions, old[field][index].dimensions);
      } else {
        assert.equal(entry.gap, old[field][index].gap);
        assert.equal(entry.benefit, old[field][index].benefit);
      }
    }
    delete task.review[coverage];
    if (!task[field].length) delete task[field];
  }
  assert.equal(compact.optimizedPrompt, original.optimizedPrompt);
  assert.deepEqual(task.openDecisions, old.openDecisions);
  if (!task.openDecisions.length) delete task.openDecisions;
  assert.deepEqual(task.review.planImpacts, []);
  delete task.review.planImpacts;
  const previousCharacters = JSON.stringify(original).length;
  const projectedCharacters = JSON.stringify(compact).length;
  rows.push({ caseId, jobId, sourceInputTokens: trace.response.usage.prompt_tokens,
    sourceOutputTokens: trace.response.usage.completion_tokens, systemCharacters: trace.request.messages[0].content.length,
    previousCharacters, projectedCharacters, optimizedPromptCharacters: original.optimizedPrompt.length,
    savedCharacters: previousCharacters - projectedCharacters, singleRootClosure,
    allSourceEvidenceAndCoveragePreserved: true });
}
const total = key => rows.reduce((sum, row) => sum + row[key], 0);
const result = { rows, previousCharacters: total('previousCharacters'), projectedCharacters: total('projectedCharacters'),
  optimizedPromptCharacters: total('optimizedPromptCharacters'), savedCharacters: total('savedCharacters'),
  actualModelCallsAdded: 0, note: '历史字符投影及真实Provider离线回归只能证明表示成本和保护记录保留，不能预测模型Token、首次成功率或作品收益；本轮真实对照另记。' };
await writeFile(resolve(evidence, 'existing-response-analysis.json'), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ cases: rows.length, previousCharacters: result.previousCharacters,
  projectedCharacters: result.projectedCharacters, savedCharacters: result.savedCharacters, actualCallsAdded: 0 }));
