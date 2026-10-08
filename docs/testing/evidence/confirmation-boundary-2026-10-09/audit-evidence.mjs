import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const root = 'tmp/confirmation-boundary-20261009';
const json = async p => JSON.parse(await readFile(p, 'utf8'));
const canonical = text => text.replace(/\s+/g, '');
const summary = { candidateCommit: '34a4496', samples: [], works: [] };
let review = '# 最终候选逐例原文与平台组装核对\n\n';
for (const dir of ['final03-flash', 'final03-pro']) {
  const names = await readdir(`${root}/${dir}`);
  const fixture = await json(`${root}/${dir}/input.json`);
  for (const sample of fixture.cases) {
    const record = await json(`${root}/${dir}/${sample.id}-generation.json`);
    const traces = await Promise.all(names.filter(n => n.startsWith(`${sample.id}-generation-trace-`)).sort((a,b)=>a.localeCompare(b, undefined, {numeric:true})).map(n=>json(`${root}/${dir}/${n}`)));
    const generationTraces = traces.filter(t => { try { return Array.isArray(JSON.parse(t.content).sections); } catch { return false; } });
    const raw = generationTraces.length ? JSON.parse(generationTraces.at(-1).content) : {};
    const row = { route:fixture.modelId,caseId:sample.id,status:record.status,elapsedMs:record.elapsedMs,
      rawChars: sample.rawPrompt.length, materialChars:sample.files.reduce((n,f)=>n+f.content.length,0),
      includeExamples:record.effectiveOptions?.includeExamples,mode:sample.mode,questions:record.plan?.questions?.length ?? 0,
      attempts:traces.length,modelMs:traces.reduce((n,t)=>n+t.elapsedMs,0),
      phaseModels:traces.map(t=>({model:t.actualModel,ms:t.elapsedMs,stop:t.finishReason,usage:t.usage})),
      optimizedChars:record.result?.optimizedPrompt?.length, failureCategory:record.failureCategory };
    summary.samples.push(row);
    review += `## ${fixture.modelId} / ${sample.id} / ${record.status}\n\n`;
    if (record.plan) review += '### 实际问题及答案\n\n' + record.plan.questions.map(q=>`- ${q.question}\n  答：${record.answers.find(a=>a.questionId === q.id)?.answer ?? '未找到'}\n`).join('') + '\n';
    review += '### 模型原文段落\n\n' + (raw.sections ?? []).map(s=>`#### ${s.type}\n\n${s.content}\n\n`).join('');
    review += '模型原文提醒：\n\n' + JSON.stringify(raw.ambiguities ?? [], null, 2) + '\n\n';
    const original = (raw.sections ?? []).map(s=>canonical(s.content)).join('\n');
    review += '### 平台段落差异（逐行原文不包含，须人工核对语义）\n\n';
    for (const section of record.result?.sections ?? []) {
      const added = section.content.split(/\r?\n/).filter(line=>line.trim() && !original.includes(canonical(line)));
      if (added.length) review += `#### ${section.type}\n\n${added.join('\n')}\n\n`;
    }
    review += '平台最终提醒：\n\n' + JSON.stringify(record.result?.ambiguities ?? [], null, 2) + '\n\n';
    for (const arm of ['raw','direct']) {
      const name=`${sample.id}-${arm}-work.json`;
      if (!names.includes(name)) continue;
      const work=await json(`${root}/${dir}/${name}`);
      summary.works.push({route:fixture.modelId,caseId:sample.id,arm,status:work.status,stop:work.finishReason,ms:work.singleHttpCallMs,chars:work.nonWhitespaceChars,sha256:work.outputSha256});
    }
  }
}
await writeFile(`${root}/final03-audit.json`, JSON.stringify(summary,null,2));
await writeFile(`${root}/final03-review.md`, review);
console.log(JSON.stringify(summary,null,2));
