import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const projectRoot = path.resolve(here, '../inputs/project');
export const editablePaths = Object.freeze([
  'src/domain/availability.ts', 'src/domain/quantity.ts', 'src/application/loanDeskService.ts',
  'src/composables/useNewLoan.ts', 'src/composables/useLoanEdit.ts',
  'src/components/AvailabilityPanel.vue', 'src/components/LoanDraftForm.vue',
  'src/views/NewLoanPage.vue', 'src/views/EditLoanPage.vue',
]);

function readTree(root, directory = root, files = {}) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) readTree(root, absolute, files);
    else files[path.relative(root, absolute).split(path.sep).join('/')] = fs.readFileSync(absolute, 'utf8');
  }
  return files;
}

export function applyArtifact(original, artifact) {
  if (!artifact || typeof artifact !== 'object' || Array.isArray(artifact)
    || Object.keys(artifact).some((key) => key !== 'files') || !Array.isArray(artifact.files)
    || artifact.files.length > editablePaths.length) throw new Error('INVALID_ARTIFACT_FORMAT');
  const files = { ...original };
  const seen = new Set();
  for (const replacement of artifact.files) {
    if (!replacement || typeof replacement !== 'object' || Array.isArray(replacement)
      || Object.keys(replacement).sort().join(',') !== 'content,path'
      || typeof replacement.path !== 'string' || typeof replacement.content !== 'string'
      || !editablePaths.includes(replacement.path) || seen.has(replacement.path)
      || replacement.content.length === 0 || replacement.content.length > 100000) {
      throw new Error('FORBIDDEN_OR_INVALID_FILE_REPLACEMENT');
    }
    seen.add(replacement.path);
    files[replacement.path] = replacement.content;
  }
  return files;
}

function dependencies(dependencyRoot) {
  const req = createRequire(path.resolve(dependencyRoot, 'package.json'));
  const ts = req('typescript');
  const sfc = req('@vue/compiler-sfc');
  const runtime = fs.readFileSync(req.resolve('vue/dist/vue.runtime.global.prod.js'), 'utf8');
  return { ts, sfc, runtime };
}

function compileFiles(files, ts, sfc) {
  const modules = {};
  const virtualName = (filename) => path.posix.normalize(filename.replace(/\\/g, '/'));
  const virtualFs = {
    fileExists: (filename) => Object.hasOwn(files, virtualName(filename)),
    readFile: (filename) => files[virtualName(filename)],
    realpath: virtualName,
  };
  for (const [filename, source] of Object.entries(files)) {
    if (!filename.startsWith('src/') || filename.endsWith('.d.ts')
      || (!filename.endsWith('.ts') && !filename.endsWith('.vue'))) continue;
    let script = source;
    if (filename.endsWith('.vue')) {
      const parsed = sfc.parse(source, { filename });
      if (parsed.errors.length) throw new Error(`SFC_PARSE_FAILED:${filename}`);
      const compiled = sfc.compileScript(parsed.descriptor, {
        id: filename, inlineTemplate: true, isProd: true,
        fs: virtualFs,
        templateOptions: { compilerOptions: { hoistStatic: false, cacheHandlers: false } },
      });
      script = compiled.content;
    }
    const compiled = ts.transpileModule(script, {
      fileName: filename.replace(/\.vue$/, '.ts'), reportDiagnostics: true,
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
        sourceMap: false, inlineSourceMap: false, importHelpers: false, esModuleInterop: true },
    });
    if (compiled.diagnostics?.some((item) => item.category === ts.DiagnosticCategory.Error)) {
      throw new Error(`TS_COMPILE_FAILED:${filename}`);
    }
    modules[filename] = compiled.outputText;
  }
  return modules;
}

function moduleBootstrap(modules) {
  const factories = Object.entries(modules).map(([filename, code]) =>
    `${JSON.stringify(filename)}:function(module,exports,require){\n${code}\n}`).join(',\n');
  return `
    const __factories = Object.freeze({${factories}});
    const __cache = Object.create(null);
    function __resolve(request, from) {
      if (request === 'vue') return 'vue';
      if (typeof request !== 'string' || !request.startsWith('.')) throw new Error('EXTERNAL_MODULE_DENIED');
      const parts = from.split('/').slice(0, -1);
      for (const part of request.split('/')) {
        if (!part || part === '.') continue;
        if (part === '..') { if (!parts.length) throw new Error('PATH_ESCAPE_DENIED'); parts.pop(); }
        else parts.push(part);
      }
      const candidate = parts.join('/');
      for (const id of [candidate, candidate + '.ts', candidate + '.vue', candidate + '/index.ts']) {
        if (Object.prototype.hasOwnProperty.call(__factories, id)) return id;
      }
      throw new Error('UNKNOWN_PROJECT_MODULE');
    }
    function __projectLoad(id) {
      if (id === 'vue') return Vue;
      if (!Object.prototype.hasOwnProperty.call(__factories, id)) throw new Error('UNKNOWN_PROJECT_MODULE');
      if (__cache[id]) return __cache[id].exports;
      const module = { exports: {} };
      __cache[id] = module;
      __factories[id](module, module.exports, (request) => __projectLoad(__resolve(request, id)));
      return module.exports;
    }
  `;
}

export function evaluateArtifact(artifact, options = {}) {
  const original = readTree(projectRoot);
  let files;
  try { files = applyArtifact(original, artifact); }
  catch (error) { return { passed: false, stage: 'artifact', error: error.message, checks: [] }; }
  const dependencyRoot = options.dependencyRoot ?? path.resolve(here, '../../../../../../apps/web');
  const { ts, sfc, runtime } = dependencies(dependencyRoot);
  let modules;
  try { modules = compileFiles(files, ts, sfc); }
  catch (error) { return { passed: false, stage: 'compile', error: error.message, checks: [] }; }
  const context = vm.createContext(Object.create(null), {
    name: 'sealed-engineering-artifact',
    codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate',
  });
  const timeout = options.timeout ?? 2500;
  try {
    new vm.Script('const console = Object.freeze({log(){},warn(){},error(){},info(){}});')
      .runInContext(context, { timeout });
    new vm.Script(runtime, { filename: 'trusted-vue-runtime.js' }).runInContext(context, { timeout });
    new vm.Script(moduleBootstrap(modules), { filename: 'compiled-project.js' }).runInContext(context, { timeout });
    const harness = fs.readFileSync(path.join(here, 'harness.js'), 'utf8');
    new vm.Script(harness, { filename: 'trusted-check-harness.js' }).runInContext(context, { timeout });
    const resultText = new vm.Script('__runEngineeringChecks();').runInContext(context, { timeout });
    if (typeof resultText !== 'string') throw new Error('CHECK_HARNESS_DID_NOT_FINISH');
    const result = JSON.parse(resultText);
    return { ...result, stage: 'runtime', compiledModules: Object.keys(modules).length,
      changedFiles: artifact.files.map((item) => item.path), hostCapabilitiesExposed: [] };
  } catch (error) {
    return { passed: false, stage: 'runtime', error: String(error?.message ?? error), checks: [] };
  }
}

const directlyInvoked = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (directlyInvoked) {
  const artifactPath = process.argv[2];
  const dependencyRoot = process.argv[3];
  if (!artifactPath) {
    console.error('Usage: node checker.mjs <artifact.json> [existing dependency directory]');
    process.exitCode = 2;
  } else {
    try {
      const artifact = JSON.parse(fs.readFileSync(path.resolve(artifactPath), 'utf8'));
      const result = evaluateArtifact(artifact, dependencyRoot ? { dependencyRoot } : {});
      console.log(JSON.stringify(result));
      process.exitCode = result.passed ? 0 : 1;
    } catch (error) {
      console.log(JSON.stringify({ infrastructureError: true, error: String(error?.message ?? error) }));
      process.exitCode = 2;
    }
  }
}
