/**
 * 分离 Plan 提问与答案提交，供评测员先逐题核对回答，避免关键词自动答错。
 * --collect <run-dir> 采集直接增强与 Plan 问题；--finish <run-dir> 提交 reviewed-answers.json。
 * 同一 run-dir 不覆盖已有文件。Plan 过期会保留失败，不重新提问挑选有利结果。
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { createAcceptanceSession } from './plan-business-acceptance.mjs';
import { redact } from './plan-quality-eval.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const [mode, directory] = process.argv.slice(2);
if (!['--collect', '--finish'].includes(mode) || !directory) throw new Error('Use --collect <run-dir> or --finish <run-dir>.');
const output = resolve(directory);
if (!output.startsWith(resolve(root, 'tmp/prompt-comparison') + sep)) throw new Error('Unsafe evidence path.');
const parse = async file => JSON.parse(await readFile(resolve(output, file), 'utf8'));
const json = data => JSON.stringify(data, (_key, value) => typeof value === 'string' ? redact(value) : value, 2);
async function save(file, data) {
  const path = resolve(output, file);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, json(data), { encoding: 'utf8', flag: 'wx' });
}

const manifest = await parse('manifest.json');
const rawCases = await readFile(resolve(output, 'cases.json'), 'utf8');
if (createHash('sha256').update(rawCases).digest('hex') !== manifest.caseSha256) throw new Error('Frozen cases changed.');
const cases = JSON.parse(rawCases);
const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0];
const page = context.pages().find(item => item.url().startsWith('http://127.0.0.1:5175/'));
if (!page) throw new Error('No local evaluation page.');
const session = await createAcceptanceSession(context, page, { baseUrl: 'http://127.0.0.1:5175', outputRoot: resolve(output, 'review-session') });
const selected = mode === '--collect' ? { model: session.model, selectedAt: new Date().toISOString() } : await parse('selected-model.json');
if (selected.model.id !== session.model.id) throw new Error('Default model changed; do not silently compare different models.');
if (mode === '--collect') await save('selected-model.json', selected);
const answersByCase = mode === '--finish' ? await parse('reviewed-answers.json') : {};
const summaries = [];
const request = (item, confirmation) => ({
  rawPrompt: item.rawPrompt, modelId: selected.model.id,
  context: { customDescription: item.contextDescription, files: item.files.map(({ path, language, content }) => ({ path, language, content })) },
  enhancement: { templateCode: 'AUTO', includeConversationHistory: false, includePermissionBoundaries: true, includeExamples: false },
  conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
  ...(confirmation ? { planConfirmation: confirmation } : {}),
});

for (const [index, item] of cases.entries()) {
  const arms = mode === '--finish' ? ['plan'] : index % 2 ? ['plan', 'direct'] : ['direct', 'plan'];
  for (const arm of arms) {
    const start = performance.now();
    const requestStart = session.requests.length;
    const evidence = { id: item.id, arm, phase: mode, startedAt: new Date().toISOString(), caseSha256: manifest.caseSha256 };
    console.log(json({ event: 'review.start', id: item.id, arm, phase: mode }));
    try {
      if (arm === 'plan' && mode === '--collect') {
        const result = await session.plan({ ...item, files: request(item).context.files });
        evidence.plan = result.plan;
        evidence.prepared = result.prepared;
        evidence.awaitingAnswerReview = true;
      } else {
        let confirmation;
        if (arm === 'plan') {
          const planned = await parse(`platform/${item.id}--plan-collected.json`);
          const reviewed = answersByCase[item.id];
          if (!reviewed || Object.keys(reviewed).length !== planned.plan.questions.length) throw new Error('Every question needs an explicit reviewed answer.');
          evidence.plan = planned.plan;
          evidence.prepared = planned.prepared;
          evidence.answers = planned.plan.questions.map(question => {
            const entry = reviewed[question.id];
            if (!entry || entry.question !== question.question || !entry.answer?.trim() || entry.answer.length > 1500 || !entry.basis?.trim()) throw new Error('Missing or mismatched answer/basis.');
            return { questionId: question.id, question: question.question, answer: entry.answer.trim() };
          });
          evidence.answerReview = reviewed;
          evidence.priorRequests = planned.requests;
          confirmation = { planId: planned.plan.planId, planningContext: planned.plan.planningContext, answers: evidence.answers };
          evidence.refinedQuery = await page.evaluate(async ({ rawPrompt, confirmation }) => {
            const { buildRefinedContextQuery } = await import('/src/features/optimization/optimizationRequest.ts');
            return buildRefinedContextQuery(rawPrompt, confirmation);
          }, { rawPrompt: item.rawPrompt, confirmation });
        }
        evidence.final = await session.call('/api/v1/optimizations', request(item, confirmation));
        evidence.protocolChecks = {
          realProvider: evidence.final.provider?.mock === false,
          promptPresent: !!evidence.final.optimizedPrompt?.trim(),
          fourSections: ['BACKGROUND', 'TASK', 'OUTPUT', 'CONSTRAINTS'].every(type => evidence.final.sections?.some(section => section.type === type && section.content?.trim())),
        };
        if (!Object.values(evidence.protocolChecks).every(Boolean)) throw new Error('Returned payload failed protocol checks.');
      }
    } catch (error) {
      evidence.error = redact(error.message);
    }
    evidence.elapsedMs = Math.round(performance.now() - start);
    evidence.requests = session.requests.slice(requestStart);
    const suffix = arm === 'direct' ? 'direct' : mode === '--collect' ? 'plan-collected' : 'plan-reviewed';
    await save(`platform/${item.id}--${suffix}.json`, evidence);
    const summary = { id: item.id, arm, phase: mode, elapsedMs: evidence.elapsedMs, questions: evidence.plan?.questions?.length,
      ambiguities: evidence.final?.ambiguities?.length, length: evidence.final?.optimizedPrompt?.length,
      protocolChecks: evidence.protocolChecks, error: evidence.error, requestId: evidence.requests.at(-1)?.requestId };
    summaries.push(summary);
    console.log(json({ event: 'review.saved', ...summary }));
  }
}
await save(mode === '--collect' ? 'collected-summary.json' : 'reviewed-summary.json', summaries);
process.exit(summaries.some(item => item.error) ? 1 : 0);
