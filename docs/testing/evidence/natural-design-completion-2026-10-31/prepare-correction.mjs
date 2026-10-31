/** 封存首轮后，建立同一假设的一次修正入口；总账继续累计原目录全部trace，不重置500次。 */
import {readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url));
let runner=await readFile(resolve(dir,'run.mjs'),'utf8');
runner=runner.replaceAll('core-design-completion-v1','core-design-completion-v2')
  .replaceAll('DesignCompletionEvaluation','DesignCompletionEvaluationV2')
  .replaceAll("'run.mjs'","'run-v2.mjs'").replaceAll("'run.ps1'","'run-v2.ps1'")
  .replaceAll("'protocol.json'","'protocol-v2.json'").replaceAll("'closure.json'","'closure-v2.json'");
await writeFile(resolve(dir,'run-v2.mjs'),runner,{flag:'wx'});
let wrapper=await readFile(resolve(dir,'run.ps1'),'utf8');
wrapper=wrapper.replaceAll('core-design-completion-v1','core-design-completion-v2').replaceAll("'run.mjs'","'run-v2.mjs'");
await writeFile(resolve(dir,'run-v2.ps1'),wrapper,{flag:'wx'});
let java=await readFile(resolve(dir,'DesignCompletionEvaluation.java'),'utf8');
java=java.replaceAll('DesignCompletionEvaluation','DesignCompletionEvaluationV2').replaceAll('core-design-completion-v1','core-design-completion-v2');
await writeFile(resolve(dir,'DesignCompletionEvaluationV2.java'),java,{flag:'wx'});
