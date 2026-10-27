import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadSyntheticProject } from './synthetic-project.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const capturePaths = process.argv[2] ? [resolve(root, process.argv[2])] : ['input.json', 'input-new-case.json'].map(path => resolve(root, 'browser-capture', path));
const output = resolve(root, process.argv[3] ?? 'browser-capture/c01-n01-bundle.json');
const fixture = await loadSyntheticProject();
const newCase = JSON.parse(await readFile(resolve(root, 'internal-new-case.json'), 'utf8'));
const cases = [];
const jobs = [];
for (const capturePath of capturePaths) {
const input = JSON.parse((await readFile(capturePath, 'utf8')).replace(/^\uFEFF/, ''));
const id = input.rawPrompt === fixture.rawPrompt ? 'C01' : input.rawPrompt === newCase.rawPrompt ? newCase.id : null;
if (!id || input.modelId !== 'deepseek:deepseek-flash' || input.enhancement?.promptFormat !== 'COMPACT'
    || (input.conversationHistory ?? []).length || input.planConfirmation) throw Error('Expected the exact synthetic direct natural request with no private conversation');
const files = input.context?.files;
if (!Array.isArray(files) || !files.length) throw Error('Actual project retrieval capture is required');
const normalizedPath = path => path.replaceAll('\\', '/').replace(/^\.\//, '').replace(/#chunk-\d+$/, '').replace(new RegExp(`^${fixture.rootName}/`), '');
for (const file of files) {
  const source = fixture.files.find(value => value.path === normalizedPath(file.path));
  if (!source || typeof file.content !== 'string' || !source.content.includes(file.content)) throw Error('Capture contains a nonfixture file or content');
}
const requested = input.rawPrompt.match(/@([^\s]+)/)?.[1];
const reference = normalizedPath(requested ?? '');
const targetPaths = fixture.targetPaths.filter(path => path === reference || path.startsWith(reference + '/'));
if (!targetPaths.length || files.some(file => normalizedPath(file.path).startsWith('src/views/records-old/'))) throw Error('Requested target missing or unrelated old module leaked');
for (const path of targetPaths) if (!files.some(file => normalizedPath(file.path) === path)) throw Error('A required target file is missing from actual browser retrieval');
const sample = { id, rawPrompt: input.rawPrompt, contextDescription: input.context.customDescription ?? '',
  projectRootName: fixture.rootName, targetPaths,
  projectFileHashes: fixture.files.map(file => ({ path: file.path, sha256: createHash('sha256').update(file.content).digest('hex') })),
  inputOrigin: 'Actual workbench folder picker -> local IndexedDB -> retrieval -> intercepted ordinary /api/v2/optimizations DTO' };
cases.push(sample);
jobs.push(...['31', '45'].map(version => ({ id: `${id}_V${version}_GEN`, phase: 'GENERATION', caseId: id, version,
  approvedEnhancementInput: input })));
}
await writeFile(output, JSON.stringify({ syntheticOnly: true, cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, cases: cases.map(value => value.id), syntheticFiles: fixture.files.length, jobs: jobs.length, targetFilesPresent: true, callsMade: 0 }));
