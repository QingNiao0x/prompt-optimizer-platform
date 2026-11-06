import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, '../../../..');
const manifestPath = join(directory, 'baseline.json');
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');

// 已冻结原件只核对摘要，后续执行不会覆盖历史捕获结果。
if (process.argv.includes('--verify')) {
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  for (const item of manifest.artifacts) {
    if (digest(readFileSync(join(directory, item.path))) !== item.sha256) {
      throw new Error(`冻结原件发生变化 ${item.path}`);
    }
  }
  for (const item of manifest.caseSources) {
    if (digest(readFileSync(join(root, item.path))) !== item.sha256) {
      throw new Error(`引用题目原件发生变化 ${item.path}`);
    }
  }
  console.log(JSON.stringify({ verifiedArtifacts: manifest.artifacts.length, modelCalls: 0 }));
  process.exit(0);
}
if (existsSync(manifestPath)) throw new Error('基线已冻结，请使用 --verify 核对原件');

const exportResult = spawnSync('java', ['--class-path', join(root, 'services/api/target/classes'),
  join(directory, 'ExportInstructions.java'), join(directory, 'instructions'), 'core-repair-v4'],
{ cwd: root, encoding: 'utf8', timeout: 30000 });
if (exportResult.error || exportResult.status !== 0) {
  throw new Error(`指导导出失败，请先完成后端编译 ${exportResult.stderr || exportResult.error?.message}`);
}

const git = (...args) => {
  const result = spawnSync('git', ['-c', 'core.excludesFile=', ...args], { cwd: root, encoding: 'utf8' });
  if (result.status !== 0) throw new Error('无法读取仓库基线');
  return result.stdout.trim();
};
const probes = await Promise.all([9000, 5173].map(async (port) => {
  const url = port === 9000 ? `http://127.0.0.1:${port}/api/v1/health` : `http://127.0.0.1:${port}/`;
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(2500), redirect: 'manual' });
    await response.body?.cancel();
    return { port, reachable: true, status: response.status };
  } catch {
    return { port, reachable: false, status: null };
  }
}));
const sourceFiles = [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptPlanContextInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptCoreRepairInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptStageInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptPreparationSourceLabels.java',
  'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementSettings.java',
  'services/api/src/main/resources/application.yml',
];
const artifactPaths = ['cases.json', ...readdirSync(join(directory, 'instructions')).map(name => `instructions/${name}`)];
const cases = JSON.parse(readFileSync(join(directory, 'cases.json'), 'utf8')).cases;
const caseSources = [...new Set(cases.flatMap(item => item.sourceCase ? [item.sourceCase.path] : []))];
const manifest = {
  recordedAt: new Date().toISOString(),
  timeZone: 'Asia/Shanghai',
  sourceCommit: git('rev-parse', 'HEAD'),
  existingChanges: git('status', '--short').split('\n').filter(Boolean),
  sourceDefaultProfile: 'PLAN_CONTEXT_REPAIR',
  sourceDefaultVersion: 'core-repair-v4',
  runtimeVersion: null,
  runtimeVerification: 'NOT_VERIFIED',
  runtimeProbes: probes,
  modelCalls: 0,
  sources: sourceFiles.map(path => ({ path, sha256: digest(readFileSync(join(root, path))) })),
  caseSources: caseSources.map(path => ({ path, sha256: digest(readFileSync(join(root, path))) })),
  artifacts: artifactPaths.map(path => {
    const bytes = readFileSync(join(directory, path));
    return { path, bytes: bytes.length, sha256: digest(bytes) };
  }),
};
writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log(JSON.stringify({ sourceCommit: manifest.sourceCommit, sourceDefaultVersion: manifest.sourceDefaultVersion,
  runtimeProbes: probes, frozenArtifacts: manifest.artifacts.length, modelCalls: 0 }));
