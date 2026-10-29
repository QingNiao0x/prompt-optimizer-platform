import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), seal = resolve(here, '../sealed-completion-holdout');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const source = await json(resolve(seal, 'input-cases.json'));
assert.equal(source.collectionId, 'completion-20261028'); assert.equal(source.cases.length, 3);
const cases = [];
for (const sample of source.cases) {
  assert.match(sample.id, /^C0[123]$/); assert.equal(typeof sample.originalPrompt, 'string');
  const id = sample.id.replace(/^C/, 'U'), directory = resolve(here, 'inputs', id);
  await mkdir(directory, { recursive: true });
  await writeFile(resolve(directory, 'prompt.md'), sample.originalPrompt, { flag: 'wx' });
  const attachments = [], materialMapping = [];
  for (let index = 0; index < sample.materials.length; index += 1) {
    const material = sample.materials[index]; assert.equal(typeof material.title, 'string'); assert.equal(typeof material.text, 'string');
    const name = `${id}-M${String(index + 1).padStart(2, '0')}.md`, content = `# ${material.title}\n\n${material.text}`;
    const path = `inputs/${id}/${name}`;
    await writeFile(resolve(here, path), content, { flag: 'wx' }); attachments.push(path);
    materialMapping.push({ sourceMaterialId: material.id, title: material.title, normalizedAttachment: path,
      sourceTextSha256: createHash('sha256').update(material.text).digest('hex'), attachmentSha256: createHash('sha256').update(content).digest('hex'),
      transformation: '仅以原始title作为Markdown标题，后面逐字保留原始text；三臂共享相同文本边界。' });
  }
  cases.push({ id, sealedCaseId: sample.id, sourceCollectionId: source.collectionId,
    prompt: `inputs/${id}/prompt.md`, attachments, materialMapping,
    actualModelInputWhitelist: ['originalPrompt', 'materials.title', 'materials.text'],
    evaluationFieldsExcluded: ['acceptance-cards.json', 'author-isolation.json', 'execution-protocol.json', 'manifest.json'] });
}
await writeFile(resolve(here, 'case-index.json'), JSON.stringify({ syntheticOnly: true, sourceCollectionId: source.collectionId, cases }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ cases: cases.map(sample => ({ id: sample.id, sealedCaseId: sample.sealedCaseId, attachments: sample.attachments.length })), modelCalls: 0 }));
