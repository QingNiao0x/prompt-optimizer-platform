import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
const root=process.cwd(),dir=path.join(root,'tmp/native-prefetch24-20261117');
const require=createRequire(path.join(root,'apps/web/package.json'));
const {build}=require('esbuild');
const common={bundle:true,alias:{'@':path.join(root,'apps/web/src')}};
await fs.writeFile(path.join(dir,'helpers.ts'),`export {projectIndexRepository} from '../../apps/web/src/features/project-index/indexedDbProjectIndexRepository'; export {createIsolatedProjectId,projectDatabaseLocation} from '../../apps/web/src/features/project-index/projectIndexStorage'; export {retrieveProjectContext} from '../../apps/web/src/features/project-index/projectIndexer';`);
await fs.writeFile(path.join(dir,'scope.ts'),`export {createProjectFileScope} from '../../apps/web/src/features/project-index/projectFileScope'; export {isSensitiveFile,isSafeRelativeFilePath} from '../../apps/web/src/workers/fileReaderCore';`);
await build({...common,entryPoints:[path.join(dir,'helpers.ts')],format:'iife',globalName:'IndexApi',outfile:path.join(dir,'helpers.js')});
await build({...common,entryPoints:[path.join(dir,'scope.ts')],platform:'node',format:'esm',outfile:path.join(dir,'scope.mjs')});
for(const variant of ['before','after']) {
 await build({...common,entryPoints:[path.join(root,'apps/web/src/workers/projectIndex.worker.ts')],format:'iife',outfile:path.join(dir,variant+'.js'),plugins:variant==='before'?[{name:'frozen-six',setup(build){build.onLoad({filter:/projectIndexer\.ts$/},args=>({contents:execFileSync('git',['show','b99e8fae:apps/web/src/features/project-index/projectIndexer.ts'],{encoding:'utf8'}),loader:'ts',resolveDir:path.dirname(args.path)}));}}]:[]});
}
console.log('native workers built');
