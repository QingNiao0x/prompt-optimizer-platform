import {readFile,access} from 'node:fs/promises';
import {resolve} from 'node:path';
const base=resolve('docs/testing/evidence/natural-routing-repair-2026-10-24');
const stage=process.argv[2]; if(!['targeted','targeted-r1','timing-advisory','plan-ownership'].includes(stage)) throw Error('Unexpected stage');
const p=JSON.parse(await readFile(resolve(base,stage,'protocol.json'),'utf8'));
if(p.status!=='FROZEN'||p.taskMaximumActualCalls!==56||![35,19].includes(p.maximumNewActualCalls)||p.qualityRetriesAllowed!==false||p.accountCreationAllowed!==false) throw Error('Budget/protocol changed');
for(const path of [base,resolve(base,stage),resolve('tmp/natural-routing-repair-2026-10-24'),resolve('tmp/natural-routing-repair-2026-10-24',stage)]) {try {await access(resolve(path,'closure.json')); throw Error('Closed batch');} catch(e) {if(e.code!=='ENOENT') throw e;}}
console.log('Frozen stage and call boundaries checked');
