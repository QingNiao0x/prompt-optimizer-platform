import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url)), repo = resolve(root, '../../../..');
const previous = JSON.parse(await readFile(resolve(root, 'candidate47-freeze.json'), 'utf8'));
const paths = [...previous.files.map(file => file.path),
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptBoundedRepairInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptRepairContext.java'];
const files = [];
for (const path of paths) files.push({ path, sha256: createHash('sha256').update(await readFile(resolve(repo, path))).digest('hex') });
for (const name of ['NaturalPromptUsefulDeliveryInstructions.java', 'NaturalPromptEffectiveRequestInstructions.java']) {
  const old = previous.files.find(file => file.path.endsWith('/' + name));
  if (files.find(file => file.path === old.path).sha256 !== old.sha256) throw Error('冻结正文指导被改动');
}
await writeFile(resolve(root, 'candidate48-freeze.json'), JSON.stringify({
  frozenAt: new Date().toISOString(), version: '48', previousFreeze: 'candidate47-freeze.json',
  defaultBeforeAdoption: '31', scope: '只改变既有一次正文协议修复，正文首稿及Plan指导逐字沿用47',
  hypothesis: '将无效候选作为数据及可定位的服务端诊断交给既有修复，减少无依据整体重生成；所有原接受条件不变',
  modelStagesAdded: 0, maximumBodyAttempts: 2, knownRegressions: ['U02', 'U03'],
  knownCasesNotClaimedAsUnseen: true, model: 'deepseek-flash', temperature: 0.2,
  thinking: 'disabled', adoptionPassed: false, files,
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ frozen: true, version: '48', files: files.length, default: '31', modelCalls: 0 }));
