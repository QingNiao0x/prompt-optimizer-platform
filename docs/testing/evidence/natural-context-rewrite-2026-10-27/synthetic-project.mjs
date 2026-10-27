import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const rootName = 'synthetic-survey-workspace';
export const targetDirectory = 'src/views/records';
export const targetPaths = ['dateRules.js', 'create.js', 'import.js'].map((name) => `${targetDirectory}/${name}`);
export const rawPrompt = `请在重点记录模块@${rootName}/${targetDirectory} 的新增和导入功能增加调查时间限制：调查时间不能超过今天。`;
const sourcePaths = ['package.json', 'README.md', ...targetPaths,
  `${targetDirectory}/dateRules.spec.js`, 'src/views/records-old/create.js'];

/** 同词噪声参与真实索引，用于检查指定目录是否会被无关大段源码挤出。 */
export async function loadSyntheticProject() {
  const files = await Promise.all(sourcePaths.map(async (path) => ({
    path, content: await readFile(new URL(`./fixtures/project/${path}`, import.meta.url), 'utf8'),
    language: path.endsWith('.js') ? 'javascript' : path.endsWith('.json') ? 'json' : 'markdown',
  })));
  for (let index = 0; index < 72; index += 1) {
    const path = `src/views/records-archive${String(index).padStart(3, '0')}/survey.js`;
    const discussion = Array.from({ length: 22 }, (_, line) =>
      `// 无关历史模块 ${index}-${line}：新增、导入、调查时间、日期和表单说明，仅用于检索噪声压力，不属于目标目录。`).join('\n');
    files.push({ path, content: `${discussion}\nexport const archivedRecord${index} = 'UNRELATED_SOURCE_${index}';\n`, language: 'javascript' });
  }
  return { rootName, rawPrompt, targetDirectory, targetPaths, files };
}

/** 只向调用者明确指定的新副本写入合成相对路径，不触及真实业务项目。 */
export async function materializeSyntheticProject(destination) {
  const root = resolve(destination);
  const fixture = await loadSyntheticProject();
  for (const file of fixture.files) {
    const path = resolve(root, file.path);
    if (!path.startsWith(`${root}${sep}`)) throw new Error('合成夹具路径越界');
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, file.content, 'utf8');
  }
  return fixture;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('用法：node synthetic-project.mjs <新项目副本目录>');
  const fixture = await materializeSyntheticProject(process.argv[2]);
  console.log(JSON.stringify({ rootName, files: fixture.files.length, targetPaths, destination: resolve(process.argv[2]) }));
}
