// Pure offline integrity checks. No network, account mutation or model requests.
import { readFile,writeFile,readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
const root=resolve('docs/testing/evidence/task-constraint-boundary-2026-10-16');
const load=async path=>JSON.parse(await readFile(resolve(root,path),'utf8'));
const hash=value=>createHash('sha256').update(value).digest('hex');
const checks=[];
const check=(name,value)=>{checks.push({name,passed:!!value});if(!value)throw new Error(name);};
const budget=await load('budget-final.json'),manifest=await load('candidate16-source.json');
const ledgerNames=(await readdir('tmp/me00a-20261012/traces')).filter(n=>/^\d+\.json$/.test(n));
const numbers=ledgerNames.map(name=>Number(name.slice(0,-5))).sort((a,b)=>a-b);
check('Ledger has exactly 262 sequential call records',numbers.length===262
  &&numbers.every((number,index)=>number===index+1));
check('Formal caps and batch purposes conserved',budget.actual.total===262&&budget.actual.baseline===70
  &&budget.actual.candidateValidation===192&&budget.formalLimits.total===275&&budget.formalLimits.candidateValidation===195
  &&budget.batch.actual===9&&budget.batch.purposes.PLATFORM_GENERATION===2
  &&budget.batch.purposes.EXECUTION===4&&budget.batch.purposes.REVIEW===3&&budget.batch.http400===1);
const protocol=JSON.parse(await readFile('tmp/me00a-20261012/protocol.json','utf8'));
check('Batch closed; unused calls not transferred',protocol.activeBatch.status==='CLOSED'
  &&protocol.activeBatch.unusedClosedNotTransferable===3&&protocol.activeBatch.maximumNewCalls===12);
for(const field of ['sourceFiles','fixtures','classFiles']){
 let same=true;for(const file of manifest[field])same&&=hash(await readFile(resolve(file.path)))===file.sha256;
 check(`Frozen ${field} unchanged`,same);
}
const locks=await load('real/copies-lock.json');
for(const [id,trace,rawTrace]of [['requests-title',258,256],['mortality-2015-2025',257,259]]){
 const input=await readFile(resolve(root,'real',id,'enhanced-actual-execution-input.md'));
 const clipboard=await readFile(resolve(root,'real',id,'clipboard-observed.md'));
 const model=await load(`real/traces/${trace}.json`);
 check(`${id}: exact native clipboard was executed`,input.equals(clipboard)
  &&input.equals(Buffer.from(model.request.messages.at(-1).content,'utf8'))
  &&hash(input)===locks[id].enhancedSha256);
 const original=await readFile(resolve(root,'real',id,'raw-actual-execution-input.md'));
 const originalCall=await load(`real/traces/${rawTrace}.json`);
 check(`${id}: unmodified original executed`,original.equals(Buffer.from(originalCall.request.messages.at(-1).content,'utf8'))
  &&hash(original)===locks[id].rawSha256);
 const history=await load(`real/${id}/checks.json`);
 check(`${id}: captured history body and sections agree`,history.copiedEqualsHistory&&history.sectionsEqual);
}
const review=await readFile(resolve(root,'real/review-input.md'));
const reviewLock=await load('real/review-input-lock.json');
check('Identical blind review input including failed alias call',hash(review)===reviewLock.sha256
  &&(await Promise.all([260,261,262].map(async trace=>hash((await load(`real/traces/${trace}.json`)).request.messages.at(-1).content)===reviewLock.sha256))).every(Boolean));
const interpretations=await load('real/review-interpretation.json');
check('Two valid independent AI reviews retained',interpretations.reviews.length===2
  &&interpretations.reviews.every(item=>item.cases.length===2));
const code=await load('real/requests-title/code-check/result.json');
check('Four verbatim implementations and independent tests compiled',code.implementations.length===4
  &&code.implementations.every(item=>item.compiled)&&code.independentTestCompiled&&code.modelAuthoredTests===0);
check('Generated-code core/conditional checks pass; raw boundary failures retained',code.tests===30
  &&code.observations.filter(item=>['CORE','ENHANCED_ONLY_CONTRACT'].includes(item.tier)).every(item=>item.status==='PASS')
  &&code.observations.filter(item=>item.status!=='PASS').length===6
  &&code.observations.filter(item=>item.status!=='PASS').every(item=>item.test.startsWith('test_raw_')));
const java=await load('verification.json');
check('348 affected Java tests, no skipped tests',java.java.tests===348&&java.java.failures===0
  &&java.java.errors===0&&java.java.skipped===0&&(await load('junit/index.json')).allCountsMatch);
const cleanup=await load('runtime-cleanup.json');
check('Owned runtime closed, user services retained, plaintext removed',cleanup.userServicesUnchanged
  &&cleanup.browserGracefullyClosed&&cleanup.testPortsRemaining.length===0&&cleanup.plaintextCredentialRemoved);
const layers=await load('real/layered-attribution.json');
check('Specific fix passes while research quality remains open',layers.specificPunctuationFix.passed
  &&layers.localAcceptance.research==='FAILED_SUBSTANTIVE_DELIVERY_GATE'&&layers.localAcceptance.overallG1toG8==='OPEN');
const report={kind:'Offline evidence consistency, not product quality tests',checks,allPassed:true};
try{await writeFile(resolve(root,'integrity-checks.json'),JSON.stringify(report,null,2),{flag:'wx'});}
catch(error){
  // An unchanged repeat may verify the saved result but cannot overwrite evidence.
  if(error.code!=='EEXIST'||JSON.stringify(await load('integrity-checks.json'))!==JSON.stringify(report))throw error;
}
console.log(JSON.stringify({integrityChecks:checks.length,allPassed:true}));
