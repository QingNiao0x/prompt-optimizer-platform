import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const rootName = 'synthetic-survey-workspace';
export const targetDirectory = 'src/views/records';
export const targetPaths = ['dateRules.js', 'create.js', 'import.js'].map(name => `${targetDirectory}/${name}`);
export const testPath = `${targetDirectory}/dateRules.spec.js`;
export const contracts = Object.freeze({
  C01: '本次只允许修改 src/views/records/dateRules.js、src/views/records/create.js、src/views/records/import.js，以及按需补充已有的 src/views/records/dateRules.spec.js 回归测试；不得新增文件或修改其他文件。',
  C02: '本次只允许修改 src/views/records/dateRules.js、src/views/records/create.js、src/views/records/import.js；不得新增文件，也不得修改测试文件或其他文件。',
});
export const rawPrompt = `请在重点记录模块@${rootName}/${targetDirectory} 的新增和导入功能增加调查时间限制：调查时间不能超过今天。`;
export const cases = ['C01', 'C02'].map(id => ({ id, rawPrompt: `${rawPrompt}\n\n${contracts[id]}`,
  contract: contracts[id], allowedWritePaths: id === 'C01' ? [...targetPaths, testPath] : targetPaths,
  todayBoundary: '允许今天，拒绝今天之后的日期',
  provenance: '旧失败的明确交付范围变体，开发/回归题，不是未见题' }));

/** 完整79文件索引，保留同名旧目录和72份同词噪声，不能通过缩小索引范围获得检索通过。 */
export async function loadSyntheticProject() {
  const paths = ['package.json', 'README.md', ...targetPaths, testPath, 'src/views/records-old/create.js'];
  const files = await Promise.all(paths.map(async path => ({ path,
    content: await readFile(new URL(`./project/${path}`, import.meta.url), 'utf8'),
    language: path.endsWith('.js') ? 'javascript' : path.endsWith('.json') ? 'json' : 'markdown' })));
  for (let index = 0; index < 72; index += 1) {
    const path = `src/views/records-archive${String(index).padStart(3, '0')}/survey.js`;
    const discussion = Array.from({ length: 22 }, (_, line) =>
      `// 无关历史模块 ${index}-${line}：新增、导入、调查时间、日期和表单说明，仅用于检索噪声压力，不属于目标目录。`).join('\n');
    files.push({ path, content: `${discussion}\nexport const archivedRecord${index} = 'UNRELATED_SOURCE_${index}';\n`, language: 'javascript' });
  }
  return { rootName, targetDirectory, targetPaths, files };
}

/** 只向指定新副本写入合成相对路径，禁止路径跳转。 */
export async function materializeSyntheticProject(destination) {
  const root = resolve(destination), fixture = await loadSyntheticProject();
  for (const file of fixture.files) {
    const path = resolve(root, file.path);
    if (!path.startsWith(`${root}${sep}`)) throw Error('合成夹具路径越界');
    await mkdir(dirname(path), { recursive: true }); await writeFile(path, file.content, 'utf8');
  }
  return fixture;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const fixture = await loadSyntheticProject();
  const manifest = { syntheticOnly: true, indexedFiles: fixture.files.length, cases,
    files: fixture.files.map(file => ({ path: file.path, sha256: createHash('sha256').update(file.content).digest('hex') })) };
  await writeFile(new URL('./manifest.json', import.meta.url), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ indexedFiles: fixture.files.length, cases: cases.map(item => item.id), modelCalls: 0 }));
}
