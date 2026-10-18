// Drives the actual workbench; stored answers come only from the current visible Plan.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { equalJson as equal } from '../me01-candidate41r2-responsibility-projection-2026-10-18/semantic-json.mjs';

const [command, caseId, selected = 'COMPACT'] = process.argv.slice(2);
const pub = resolve('docs/testing/evidence/me02-candidate42-source-ownership-2026-10-19');
const root = resolve('tmp/me02-candidate42r2-20261019');
const cases = JSON.parse(await readFile(resolve(pub, 'request-cases.json'), 'utf8'));
const sample = cases.find(row => row.id === caseId);
if (!sample || !['plan', 'finish', 'direct', 'regenerate'].includes(command) || !sample.promptFormats.includes(selected)) throw Error('Frozen operation required');
const out = resolve(root, 'flow', caseId), stage = resolve(out, selected);
const origin = 'http://127.0.0.1:5174', version = 'candidate42r2-source-ownership';
const hash = value => createHash('sha256').update(value).digest('hex');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const newline = text => text.replace(/\r\n/g, '\n');
const save = async (path, value) => { await mkdir(dirname(path), { recursive: true });
  const bytes = typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`;
  try { await writeFile(path, bytes, { flag: 'wx' }); }
  catch (error) { if (error.code !== 'EEXIST' || await readFile(path, 'utf8') !== bytes) throw error; }
};
async function preflight() {
  const protocol = await json('tmp/me00a-20261012/protocol.json'), batch = protocol.activeBatch;
  if (batch.version !== version || batch.status !== 'OPEN' || batch.sourceCandidate !== 'candidate42r2'
      || batch.maximumNewCalls !== 36 || batch.callsBefore !== 442 || batch.qualityRetriesAllowed !== false
      || protocol.maximumActualModelCalls !== 500 || protocol.phaseLimits.baseline !== 80
      || protocol.phaseLimits.candidateValidation !== 420) throw Error('Frozen budget/authorization mismatch');
  const bytes = await readFile(resolve(pub, 'candidate42r2-source.json'));
  if (hash(bytes) !== batch.sourceManifestSha256 || hash(await readFile(resolve(pub, 'request-cases.json'))) !== batch.casesSha256) throw Error('Manifest/input drift');
  for (const row of JSON.parse(bytes).sourceFiles) if (hash(await readFile(row.path)) !== row.sha256) throw Error('Frozen source drift');
  const files = (await readdir('tmp/me00a-20261012/traces')).filter(name => /^\d+\.json$/.test(name));
  if (files.length >= 478) throw Error('Batch exhausted');
}
await preflight();
const require = createRequire(resolve('apps/web/package.json'));
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0], account = await json('tmp/local-acceptance/account.json');
const pageName = `${version}-${caseId}`;
let page, plan, platform, planCapture, platformCapture, guardFailure;
const pending = [], counts = { plan: 0, enhancement: 0 };
const api = path => page.evaluate(async path => { const response = await fetch(path); return { status: response.status, body: await response.json() }; }, path);
const snapshot = () => page.evaluate(async () => { const { useOptimizationStore } = await import('/src/stores/optimization.ts'); const store = useOptimizationStore();
  return JSON.parse(JSON.stringify({ rawPrompt: store.rawPrompt, promptFormat: store.promptFormat, result: store.result, plan: store.plan, errorMessage: store.errorMessage })); });
async function verifyChain(before) {
  if (platform?.status !== 200 || platform.body.data.provider.mock !== false || platform.body.data.promptFormat !== selected) throw Error(`Enhancement failed ${platform?.status}`);
  const state = await snapshot(); if (!equal(state.result, platform.body.data)) throw Error('Current result differs');
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin }); await page.bringToFront();
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.copy-main-button')?.textContent?.includes('已复制'));
  const copy = await page.evaluate(() => navigator.clipboard.readText()); await save(resolve(stage, 'native-copy.md'), copy);
  if (newline(copy) !== newline(platform.body.data.optimizedPrompt)) throw Error('Native clipboard differs');
  const after = await api('/api/v1/optimization-history?current=1&size=30');
  const previous = new Set(before.body.data.records.map(row => row.id)); let found;
  for (const row of after.body.data.records) {
    if (previous.has(row.id)) continue;
    const detail = await api(`/api/v1/optimization-history/${row.id}`), value = detail.body.data;
    if (detail.status !== 200 || value.rawPrompt !== sample.rawPrompt || value.optimizedPrompt !== platform.body.data.optimizedPrompt) continue;
    for (const key of ['sections','promptFormat','ambiguities','appliedConstraints','constraintGroups']) if (!equal(value[key], platform.body.data[key])) throw Error(`History differs: ${key}`);
    found = detail; break;
  }
  if (!found) throw Error('New corresponding history missing');
  await save(resolve(stage, 'history.private.json'), found);
  const check = { success: true, caseId, promptFormat: selected, sameAccount: true, newAccountsCreated: 0,
    nativeCopyMatches: true, historyMatches: true, historyTotalDelta: after.body.data.total - before.body.data.total,
    copySha256: hash(copy), actualAnswers: sample.planConfirmed ? (await json(resolve(out,'actual-confirmation.private.json'))).answers : [], qualityRetriesAllowed: false };
  await save(resolve(stage, 'check.json'), check); return check;
}
try {
  if (['plan','direct'].includes(command)) {
    page = await context.newPage(); await page.goto(`${origin}/workbench`, { waitUntil: 'domcontentloaded' });
    await page.evaluate(({ pageName, enabled }) => { window.name = pageName; localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({ enabled, introSeen: true })); }, { pageName, enabled: sample.planConfirmed });
    await page.reload({ waitUntil: 'domcontentloaded' });
  } else {
    for (const candidate of context.pages()) if (await candidate.evaluate(() => window.name).catch(() => '') === pageName) page = candidate;
    if (!page) throw Error('Original task page missing');
    plan = await json(resolve(out,'plan.private.json'));
  }
  page.setDefaultTimeout(20000);
  const me = await api('/api/v1/auth/me');
  if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId
      || account.workspaceId && me.body.data.workspaceId !== account.workspaceId) throw Error('Canonical account mismatch');
  const before = await api('/api/v1/optimization-history?current=1&size=30');
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (response.request().method() !== 'POST' || !['/api/v1/optimizations/plan','/api/v1/optimizations'].includes(path)) return;
    const task = (async () => { const captured = { status: response.status(), body: JSON.parse((await response.body()).toString('utf8')) };
      if (path.endsWith('/plan')) { plan = captured; await save(resolve(out,'plan.private.json'),captured); }
      else { platform = captured; await save(resolve(stage,'platform.private.json'),captured); } })();
    pending.push(task); if (path.endsWith('/plan')) planCapture=task; else platformCapture=task;
  });
  await page.route(/\/api\/v1\/optimizations(?:\/plan)?(?:\?.*)?$/, async route => {
    const request=route.request(), path=new URL(request.url()).pathname;
    if (request.method() !== 'POST') return route.continue();
    try {
      await preflight(); const body=request.postDataJSON();
      if (body.rawPrompt !== sample.rawPrompt || body.modelId !== 'deepseek:deepseek-flash' || body.conversationHistory.length) throw Error('Task/model changed');
      if (path.endsWith('/plan')) {
        if (command !== 'plan' || ++counts.plan !== 1 || !sample.planConfirmed || body.contextDescription || body.planningContext) throw Error('Unexpected Plan');
        await save(resolve(out,'plan-request.private.json'),body);
      } else {
        if (planCapture) await planCapture;
        if (++counts.enhancement !== 1 || body.enhancement.promptFormat !== selected
            || body.enhancement.includeExamples !== sample.includeExamples || body.context.files.length
            || body.context.customDescription || !['AUTO', 'GENERAL', 'FEATURE_DEVELOPMENT'].includes(body.enhancement.templateCode)
            || body.permissionPolicy.protectedPaths.length || body.permissionPolicy.requireConfirmationFor.length) throw Error('Enhancement scope changed');
        if (sample.planConfirmed) {
          if (plan?.status !== 200) throw Error('Actual Plan required');
          const questions=plan.body.data.questions, answers=body.planConfirmation?.answers;
          if (body.planConfirmation?.planId !== plan.body.data.planId || !equal(body.planConfirmation.planningContext,plan.body.data.planningContext)
              || !Array.isArray(answers) || answers.length !== questions.length) throw Error('Plan binding changed');
          const prepared = questions.length ? await json(resolve(out,'prepared-answers.json')) : [];
          if (!equal(answers,prepared) || answers.some((answer,i) => answer.questionId !== questions[i].id || answer.question !== questions[i].question)) throw Error('Actual answer changed');
          await save(resolve(out,'actual-confirmation.private.json'),body.planConfirmation);
        } else if (body.planConfirmation !== null) throw Error('Unexpected confirmation');
        if (command === 'regenerate') {
          const previous=await json(resolve(out,'COMPACT/enhancement-request.private.json'));
          previous.enhancement.promptFormat=selected;
          if (!equal(body,previous)) throw Error('Mode regeneration changed original input or actual confirmation');
        }
        await save(resolve(stage,'enhancement-request.private.json'),body);
      }
      await route.continue();
    } catch(error) { guardFailure=error.message; await route.abort('blockedbyclient'); }
  });
  if (['plan','direct'].includes(command)) {
    await page.waitForSelector('#workbench-model');
    await page.evaluate(async () => { const {useOptimizationStore}=await import('/src/stores/optimization.ts');const store=useOptimizationStore();
      if(store.isLoadingModels || !store.availableModels.length) await store.refreshAvailableModels();store.selectedModelId='deepseek:deepseek-flash'; });
    await page.getByLabel('原始提示词',{exact:true}).fill(sample.rawPrompt);
    for(const [label,value] of [['结构化提示词',selected==='STRUCTURED'],['要求回答包含示例',sample.includeExamples]]) {
      const control=page.getByRole('switch',{name:label,exact:true});
      if((await control.getAttribute('aria-checked')==='true')!==value) await control.locator('..').click();
    }
  }
  const enhanced=page.waitForResponse(r=>r.request().method()==='POST' && new URL(r.url()).pathname==='/api/v1/optimizations',{timeout:150000});void enhanced.catch(()=>undefined);
  if(command==='plan') {
    const planned=page.waitForResponse(r=>r.request().method()==='POST' && new URL(r.url()).pathname==='/api/v1/optimizations/plan',{timeout:150000});
    await page.getByRole('button',{name:'先确认并增强',exact:true}).click();await planned;await planCapture;
    if(plan.status!==200)throw Error(`Plan failed ${plan.status}`);
    await save(resolve(out,'plan-observation.json'),plan.body.data);
    if(plan.body.data.questions.length) console.log(JSON.stringify({stage:'PLAN_CAPTURED',caseId,questions:plan.body.data.questions}));
    else {await enhanced;await platformCapture;console.log(JSON.stringify(await verifyChain(before)));}
  } else if(command==='finish') {
    const answers=await json(resolve(out,'prepared-answers.json'));
    for(let i=0;i<answers.length;i++) {
      const q=plan.body.data.questions[i], answer=answers[i];
      if(q.id!==answer.questionId || q.question!==answer.question)throw Error('Answer source changed');
      const dialog=page.locator('.el-dialog:visible').filter({hasText:'生成前确认'});
      await dialog.getByRole('heading',{name:q.question,exact:true}).waitFor();
      await dialog.locator('textarea:visible').fill(answer.answer);
      await dialog.getByRole('button',{name:i===answers.length-1?'生成最终提示词':'下一题',exact:true}).click();
    }
    await enhanced;await platformCapture;console.log(JSON.stringify(await verifyChain(before)));
  } else if(command==='direct') {
    await page.getByRole('button',{name:'直接增强提示词',exact:true}).click();await enhanced;await platformCapture;console.log(JSON.stringify(await verifyChain(before)));
  } else {
    const previous=await snapshot();await page.getByRole('switch',{name:'结构化提示词',exact:true}).locator('..').click();
    const toggled=await snapshot();if(!equal(previous.result,toggled.result) || !equal(previous.plan,toggled.plan))throw Error('Mode toggle changed current content');
    await page.getByRole('button',{name:'基于原始需求按所选模式重新增强',exact:true}).click();await enhanced;await platformCapture;
    const check=await verifyChain(before);await save(resolve(stage,'mode-regeneration-check.json'),{...check,togglePreservedContent:true,originalRequestAndActualAnswersReused:true});console.log(JSON.stringify(check));
  }
  await Promise.all(pending);if(guardFailure)throw Error(guardFailure);
  await page.screenshot({path:resolve(out,`${command}-${selected}.private.png`),fullPage:true});
}catch(error){await Promise.allSettled(pending);await save(resolve(out,`${command}-${selected}-failure.json`),{reason:error.message,guardFailure,counts});
  console.log(JSON.stringify({success:false,caseId,command,reason:error.message,guardFailure,counts}));process.exitCode=1;
}finally{await browser.close();}
