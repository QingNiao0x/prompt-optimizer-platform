import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = dirname(fileURLToPath(import.meta.url)), repo = resolve(root, '../../../..');
const before = JSON.parse(await readFile(resolve(root, 'frontend-r2-freeze.json'), 'utf8'));
const paths = [...before.files.map(entry => entry.path),
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptEffectiveRequestInstructions.java'];
const files = [];
for (const path of paths) files.push({ path, sha256: createHash('sha256').update(await readFile(resolve(repo, path))).digest('hex') });
const frozen46 = before.files.find(entry => entry.path.endsWith('/NaturalPromptUsefulDeliveryInstructions.java'));
if (files.find(entry => entry.path === frozen46.path).sha256 !== frozen46.sha256) throw Error('Frozen46 instruction changed');
await writeFile(resolve(root, 'candidate47-freeze.json'), JSON.stringify({ frozenAt: new Date().toISOString(),
  version: '47', revision: 'effective-confirmation', defaultBeforeAdoption: '31', previousFreeze: 'frontend-r2-freeze.json',
  hypothesis: 'Only resolve current effective requirements using actual scoped answers; retain original sources internally instead of repeating superseded conditions as active instructions',
  bodyGuidanceOtherThanConfirmationUnchanged: true, planGuidanceUnchanged: true,
  failedAblationsNotAdopted: ['Output-protocol removal', 'Derived taskScope removal', 'Mechanical editorial-duty labels'],
  holdoutOpenedByImplementer: false, holdoutManifestSha256: '0e7ef34d134d57736b04e6747a987cae790e227ad2356e7e07f63c2c776fbab0',
  model: 'deepseek-flash', temperature: 0.2, thinking: 'disabled', files,
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ frozen: true, version: '47', files: files.length, holdoutOpened: false }));
