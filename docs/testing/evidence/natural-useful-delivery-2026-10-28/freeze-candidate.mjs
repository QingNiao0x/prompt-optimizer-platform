import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const repo = resolve(root, '../../../..');
const files = [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptRoutingRepairInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptContextualRewriteInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptUsefulDeliveryInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'apps/web/src/features/project-index/projectIndexer.ts',
  'apps/web/src/features/project-index/projectPathReferences.ts',
  'apps/web/src/features/project-index/indexedDbProjectIndexRepository.ts',
  'apps/web/src/stores/optimization.ts',
  'apps/web/src/pages/PromptWorkbenchPage.vue',
];
const manifest = [];
for (const path of files) manifest.push({ path, sha256: createHash('sha256').update(await readFile(resolve(repo, path))).digest('hex') });
const path = resolve(root, 'candidate-freeze.json');
const freeze = { frozenAt: new Date().toISOString(), version: '46', defaultBeforeAdoption: '31',
  holdoutManifestSha256DeclaredByAuthor: '0e7ef34d134d57736b04e6747a987cae790e227ad2356e7e07f63c2c776fbab0',
  holdoutOpenedByImplementer: false, model: 'deepseek-flash', temperature: 0.2, thinking: 'disabled',
  hypothesis: 'Reusable 45 generation/context repair plus useful task-content completion; same delivery contract supplied before enhancement and execution',
  files: manifest };
await writeFile(path, JSON.stringify(freeze, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ version: freeze.version, files: manifest.length, frozen: true, holdoutOpened: false }));
