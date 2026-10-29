import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { lstat, mkdir, readFile, realpath, stat, symlink, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../../../..');
const sealed = resolve(here, '../sealed-engineering-holdout');
const artifactRoot = resolve(here, 'work-results/engineering-works47');
const attempt = process.argv[2] ?? '';
assert.match(attempt, /^(?:r\d+)?$/);
const suffix = attempt ? `-${attempt}` : '';
const isolatedRoot = resolve(repo, `tmp/e01-semantic-2026-10-28${suffix}`);
const resultRoot = resolve(here, `semantic-results${suffix}`);
const hash = (value) => createHash('sha256').update(value).digest('hex');
const json = async (file) => JSON.parse(await readFile(file, 'utf8'));
const within = (target, parent) => {
  const rel = relative(parent, target);
  return rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel);
};
const mustNotExist = async (target) => {
  try { await lstat(target); throw new Error('Refusing to reuse or delete an existing directory or link'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
};
assert.ok(isAbsolute(isolatedRoot) && within(isolatedRoot, repo));
await mustNotExist(isolatedRoot);
await mustNotExist(resultRoot);
const dependencyRoot = await realpath(resolve(repo, 'apps/web/node_modules'));
assert.ok(isAbsolute(dependencyRoot) && within(dependencyRoot, repo));
assert.ok((await stat(dependencyRoot)).isDirectory());
const require = createRequire(resolve(repo, 'apps/web/package.json'));
const packageInfo = {};
for (const name of ['vue-tsc', 'typescript', 'vue', '@vue/compiler-sfc']) {
  const packagePath = await realpath(require.resolve(`${name}/package.json`));
  assert.ok(within(packagePath, repo));
  const metadata = await json(packagePath);
  packageInfo[name] = { version: metadata.version, packagePath, packageSha256: hash(await readFile(packagePath)) };
}
const vueTscPackagePath = packageInfo['vue-tsc'].packagePath;
const vueTscPackage = await json(vueTscPackagePath);
const cli = await realpath(resolve(dirname(vueTscPackagePath), vueTscPackage.bin['vue-tsc']));
assert.ok(within(cli, repo));
const cliSha256 = hash(await readFile(cli));
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
const sourceFiles = manifest.files.filter((entry) => entry.path.startsWith('inputs/project/'));
assert.equal(sourceFiles.length, 30);
const base = Object.fromEntries(await Promise.all(sourceFiles.map(async (entry) =>
  [entry.path.slice('inputs/project/'.length), await readFile(resolve(sealed, entry.path))])));
const contract = await json(resolve(here, 'execution-contract.json'));
assert.equal(contract.allowedReplacementPaths.length, 9);
const variants = { BASELINE: { ...base } };
const artifactHashes = {};
for (const arm of ['ORIGINAL', '31', '47']) {
  const id = `E01_${arm === 'ORIGINAL' ? arm : `V${arm}`}_EXEC`;
  const artifactBytes = await readFile(resolve(artifactRoot, `${id}-artifact.json`));
  const artifact = JSON.parse(artifactBytes);
  assert.ok(Array.isArray(artifact.files));
  const overlaid = { ...base };
  const seen = new Set();
  for (const file of artifact.files) {
    assert.ok(contract.allowedReplacementPaths.includes(file.path));
    assert.ok(!seen.has(file.path));
    assert.equal(typeof file.content, 'string');
    seen.add(file.path);
    overlaid[file.path] = Buffer.from(file.content, 'utf8');
  }
  variants[arm] = overlaid;
  artifactHashes[arm] = hash(artifactBytes);
}
const treeRows = (tree) => Object.keys(tree).sort().map((path) => ({ path, sha256: hash(tree[path]), bytes: tree[path].length }));
const treeHash = (tree) => hash(JSON.stringify(treeRows(tree)));
assert.equal(treeHash(variants.ORIGINAL), treeHash(variants['31']));
for (const tree of Object.values(variants)) {
  assert.equal(Object.keys(tree).length, 30);
  assert.ok(tree['tsconfig.json'].equals(base['tsconfig.json']));
  assert.ok(tree['package.json'].equals(base['package.json']));
}
await mkdir(isolatedRoot);
await mkdir(resultRoot);
const prepared = [];
for (const arm of ['BASELINE', 'ORIGINAL', '47']) {
  const directory = resolve(isolatedRoot, arm.toLowerCase());
  assert.ok(within(directory, isolatedRoot));
  await mustNotExist(directory);
  await mkdir(directory);
  for (const [filename, bytes] of Object.entries(variants[arm])) {
    const target = resolve(directory, filename);
    assert.ok(within(target, directory));
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, bytes, { flag: 'wx' });
  }
  const link = resolve(directory, 'node_modules');
  assert.ok(within(link, isolatedRoot));
  await mustNotExist(link);
  // Junction creation is the only dependency wiring. Both the absolute target
  // and new link stay inside this repository, and no existing path is removed.
  await symlink(dependencyRoot, link, 'junction');
  assert.ok((await lstat(link)).isSymbolicLink());
  assert.equal(await realpath(link), dependencyRoot);
  prepared.push({ arm, directory, dependencyLink: link, dependencyTarget: dependencyRoot,
    sourceTreeSha256: treeHash(variants[arm]), sourceFileHashes: treeRows(variants[arm]) });
}

// Only the trusted compiler CLI is executed. The model package's scripts,
// vite.config.ts, source imports, and application startup are never executed.
// No parent credentials or other environment variables are inherited.
const compilerEnv = { NO_COLOR: '1' };
for (const key of ['SystemRoot', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP']) {
  if (typeof process.env[key] === 'string') compilerEnv[key] = process.env[key];
}
const runCompiler = async (variant) => {
  const args = [cli, '--noEmit', '--project', resolve(variant.directory, 'tsconfig.json'), '--pretty', 'false'];
  const startedAt = new Date().toISOString();
  const outcome = await new Promise((fulfill, reject) => {
    const child = spawn(process.execPath, args, { cwd: variant.directory, shell: false,
      windowsHide: true, env: compilerEnv, stdio: ['ignore', 'pipe', 'pipe'] });
    const stdout = [], stderr = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    child.stderr.on('data', (chunk) => stderr.push(chunk));
    child.on('error', reject);
    child.on('close', (exitCode, signal) => fulfill({ exitCode, signal,
      stdout: Buffer.concat(stdout).toString('utf8'), stderr: Buffer.concat(stderr).toString('utf8') }));
  });
  for (const [filename, bytes] of Object.entries(variants[variant.arm])) {
    assert.ok((await readFile(resolve(variant.directory, filename))).equals(bytes));
  }
  await writeFile(resolve(resultRoot, `${variant.arm}-stdout.txt`), outcome.stdout, { flag: 'wx' });
  await writeFile(resolve(resultRoot, `${variant.arm}-stderr.txt`), outcome.stderr, { flag: 'wx' });
  return { ...variant, command: { executable: process.execPath, args, shell: false, scriptsExecuted: false,
      parentEnvironmentInherited: false, environmentKeysOnly: Object.keys(compilerEnv) },
    startedAt, finishedAt: new Date().toISOString(), exitCode: outcome.exitCode, signal: outcome.signal,
    passed: outcome.exitCode === 0, stdoutFile: `${variant.arm}-stdout.txt`, stderrFile: `${variant.arm}-stderr.txt`,
    stdoutSha256: hash(outcome.stdout), stderrSha256: hash(outcome.stderr),
    diagnostics: [...outcome.stdout.matchAll(/(?:^|\n)([^\n]*error TS\d+:[^\n]*)/g)].map((match) => match[1]) };
};
const executed = await Promise.all(prepared.map(runCompiler));
const original = executed.find((entry) => entry.arm === 'ORIGINAL');
const reused31 = { arm: '31', reusedFrom: 'ORIGINAL', compilerInvocationReused: true,
  reason: 'All30 overlaid source/config files are byte-identical, not merely behaviorally equivalent.',
  sourceTreeSha256: treeHash(variants['31']), originalSourceTreeSha256: treeHash(variants.ORIGINAL),
  sourceFileHashes: treeRows(variants['31']), passed: original.passed, exitCode: original.exitCode,
  diagnostics: original.diagnostics, stdoutFile: original.stdoutFile, stderrFile: original.stderrFile };
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
for (const arm of ['ORIGINAL', '31', '47']) {
  const id = `E01_${arm === 'ORIGINAL' ? arm : `V${arm}`}_EXEC`;
  assert.equal(hash(await readFile(resolve(artifactRoot, `${id}-artifact.json`))), artifactHashes[arm]);
}
const baseline = executed.find((entry) => entry.arm === 'BASELINE');
const report = {
  caseId: 'E01', validation: 'Full vue-tsc TypeScript semantic --noEmit check with unchanged sealed tsconfig',
  isolatedRoot, sourceManifestSha256: hash(manifestBytes), sealedTsconfigSha256: hash(base['tsconfig.json']),
  trustedCompiler: { executable: process.execPath, cli, cliSha256, packages: packageInfo },
  modelCalls: 0, packageScriptsExecuted: false, dependencyInstalls: 0, artifactApplicationStartupExecuted: false,
  credentialsOrParentEnvironmentPassedToModelSource: false, existingDirectoriesDeleted: false,
  sourceFileCountPerArm: 30, permittedReplacementPaths: contract.allowedReplacementPaths,
  baseline, arms: [original, reused31, executed.find((entry) => entry.arm === '47')],
  fixtureBaselinePassed: baseline.passed,
  allWorkArmsPassed: [original, reused31, executed.find((entry) => entry.arm === '47')].every((entry) => entry.passed),
  failuresPreserved: true, sourceAndArtifactsUnchanged: true,
  interpretation: 'Runtime28 and full semantic results are separate evidence. No failed artifact or fixture is repaired, and no writer guidance is changed.',
};
await writeFile(resolve(resultRoot, 'summary.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ caseId: 'E01', modelCalls: 0, compiler: `vue-tsc ${packageInfo['vue-tsc'].version}`,
  baselinePassed: baseline.passed, arms: report.arms.map((entry) => ({ arm: entry.arm, passed: entry.passed,
    exitCode: entry.exitCode, reusedFrom: entry.reusedFrom ?? null, diagnostics: entry.diagnostics })),
  packagesInstalled: 0, packageScriptsExecuted: false }));
