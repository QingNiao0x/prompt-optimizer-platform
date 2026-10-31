/** 离线核对冻结的作品规则；代码已人工检查后才在无文件/网络能力的VM中运行，不调用模型。 */
import {readFile, writeFile} from 'node:fs/promises';
import {createContext, runInContext} from 'node:vm';
import assert from 'node:assert/strict';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const json = async name => JSON.parse(await readFile(resolve(dir, name), 'utf8'));
const results = [];
for (const task of ['W_CODE', 'D_PLAN']) {
  for (const arm of ['original', 'candidate']) {
    for (const round of [1, 2]) {
      const name = `${task}_${arm}_${round}`;
      const response = await json(`works/${name}.json`);
      const text = response.body.choices[0].message.content;
      const checks = [];
      const check = (label, fn) => {
        try { fn(); checks.push({label, passed: true}); }
        catch (error) { checks.push({label, passed: false, error: error.message}); }
      };
      if (task === 'W_CODE') {
        let source;
        check('Only requested file, valid JSON and no dependency', () => {
          const payload = JSON.parse(text);
          assert.deepEqual(Object.keys(payload), ['files']);
          assert.equal(payload.files.length, 1);
          assert.equal(payload.files[0].path, 'src/limits.mjs');
          source = payload.files[0].content;
          assert.equal(typeof source, 'string');
          assert.doesNotMatch(source, /\b(?:import|require|process|globalThis|fetch|eval)\b/);
        });
        if (source) {
          const context = createContext({}, {codeGeneration: {strings: false, wasm: false}});
          runInContext(source.replace('export function cleanBatch', 'function cleanBatch'), context, {timeout: 1000});
          const evaluate = expression => runInContext(expression, context, {timeout: 1000});
          const value = expression => JSON.parse(evaluate(`JSON.stringify(${expression})`));
          check('Empty batch', () => assert.deepEqual(value('cleanBatch([])'), {values: [], errors: []}));
          check('Trim, preserve order and duplicates', () => assert.deepEqual(value('cleanBatch([" b ", "a", "b"])'), {values: ['b', 'a', 'b'], errors: []}));
          check('Mixed invalid items are local failures with original indices', () => {
            const out = value('cleanBatch([" a ", 7, " ", null, "x".repeat(21), "b", {}, "a"])');
            assert.deepEqual(out.values, ['a', 'b', 'a']);
            assert.deepEqual(out.errors.map(x => x.index), [1, 2, 3, 4, 6]);
            assert(out.errors.every(x => typeof x.reason === 'string' && x.reason.trim()));
          });
          check('Length boundary applies after trimming', () => {
            const out = value('cleanBatch([" " + "x".repeat(20) + " ", "x".repeat(21)])');
            assert.deepEqual(out.values, ['x'.repeat(20)]);
            assert.equal(out.errors.length, 1);
            assert.equal(out.errors[0].index, 1);
          });
          check('Input is not modified', () => assert.equal(evaluate('(() => { const input = Object.freeze([" a ", null, "a"]); const before = JSON.stringify(input); cleanBatch(input); return JSON.stringify(input) === before; })()'), true));
          check('Non-array top level throws TypeError', () => assert.equal(evaluate('([null, undefined, {}, "a", 3]).every(input => { try { cleanBatch(input); return false; } catch (error) { return error instanceof TypeError; } })'), true));
        }
      } else {
        // 按预冻“100字以内”检查非空白字符；不在结果出现后放宽到100个汉字。
        const characters = [...text.replace(/\s/gu, '')].length;
        check('No more than 100 non-whitespace characters', () => assert(characters <= 100, `${characters} > 100`));
        checks.push({label: 'Semantic review: notice delivered directly, no concrete date or compensation promise, no repeated question', passed: true, reviewer: 'developer', independentHumanReview: false});
        results.push({name, characters, checks, passed: checks.every(x => x.passed)});
        continue;
      }
      results.push({name, checks, passed: checks.every(x => x.passed)});
    }
  }
}
const report = {
  assessedAt: new Date().toISOString(), rules: 'works-protocol.json',
  codeExecution: 'Reviewed source, VM with no host filesystem/network bindings, 1-second execution timeout; no model call.',
  noticeReview: 'All four directly deliver a notice without asking again or promising a date/compensation. Original round 2 adds an unprovided project-progress explanation. Candidate has generic progress wording, not evidence of the real project state. No independent human preference evaluation.',
  limitations: 'Two synthetic tasks, two executions per arm; no v3 work comparison because prior corresponding v3 generation failed. Code tie is not a quality gain.',
  results,
};
await writeFile(resolve(dir, 'work-checks.json'), JSON.stringify(report, null, 2) + '\n', {flag: 'wx'});
console.log(JSON.stringify(results.map(({name, characters, passed, checks}) => ({name, characters, passed, checks: checks.length, failures: checks.filter(x => !x.passed)})), null, 2));
