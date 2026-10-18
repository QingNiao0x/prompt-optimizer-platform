"""Check unchanged authored files using explicit artifact bindings, not a fixed function/layout."""
from pathlib import Path
import hashlib,json,re,subprocess,os,shutil

PUBLIC=Path(__file__).resolve().parent
ROOT=PUBLIC.parents[3]
LIVE=ROOT/'tmp/me01-candidate41r2-live-20261018'
sha=lambda value:hashlib.sha256(value).hexdigest()

def run(args,cwd,log):
    env={key:value for key,value in os.environ.items() if key.upper() in {'SYSTEMROOT','WINDIR','PATH','TEMP','TMP','PATHEXT','COMSPEC'}}
    result=subprocess.run(args,cwd=cwd,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    log.write_text(result.stdout+result.stderr,encoding='utf-8')
    return result

def main():
    spec_bytes=(PUBLIC/'work-check-spec.json').read_bytes();spec=json.loads(spec_bytes)
    bindings=json.loads((LIVE/'work-artifact-bindings.json').read_text(encoding='utf-8'))
    node=shutil.which('node');assert node
    result={}
    for arm in ['RAW','PLAN']:
        folder=LIVE/'works/TS'/arm;body=(folder/'work.md').read_bytes();binding=bindings[arm]
        assert binding['workSha256']==sha(body),'Artifact binding drift'
        blocks=list(re.finditer(r'^```(?:ts|typescript)\s*\n(.*?)^```',body.decode('utf-8'),re.M|re.S))
        classified=[item['block'] for item in binding['files']]+[item['block'] for item in binding.get('unselectedCodeBlocks',[])]
        assert sorted(classified)==list(range(len(blocks))),'Every TypeScript block needs an explicit artifact or exclusion reason'
        work=folder/'independent-artifact-check';work.mkdir(exist_ok=False)
        extracted=[];files=[]
        for item in binding['files']:
            assert re.fullmatch(r'(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+(?:\.test|\.spec)?\.ts',item['name']),'Local TS path required'
            assert item['kind'] in ['implementation','test'] and 0<=item['block']<len(blocks)
            text=blocks[item['block']].group(1)
            if re.search(r'child_process|process\.env|https?://|fetch\(',text):
                raise RuntimeError('Artifact needs manual execution-scope review; source not repaired')
            target=work/item['name']
            assert target.resolve().is_relative_to(work.resolve()),'Artifact path escaped local directory'
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(text,encoding='utf-8',newline='')
            extracted.append({**item,'sha256':sha(text.encode())});files.append(item['name'])
        assert len(files)==len(set(files)) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*',binding['entryExport'])
        assert binding['entryFile'] in files
        (work/'package.json').write_text('{"type":"commonjs"}\n',encoding='utf-8')
        compiler=[node,str(ROOT/'apps/web/node_modules/typescript/bin/tsc'),*files,'--target','es2019','--module','commonjs',
                  '--esModuleInterop','true','--types','node','--typeRoots',str(ROOT/'apps/web/node_modules/@types'),
                  '--noEmitOnError','--outDir','out']
        compiled=run(compiler,work,work/'joint-compile.log')
        row={'workSha256':sha(body),'specSha256':sha(spec_bytes),'sourceRepaired':False,'extractedFiles':extracted,
             'unselectedCodeBlocks':binding.get('unselectedCodeBlocks',[]),'jointCompileExitCode':compiled.returncode,
             'compilerProfile':'ES2019/CommonJS/esModuleInterop using installed Node types',
             'authorRunInstructionsChecked':'MANUAL_PENDING','commonContractState':'NOT_RUN',
             'independent':{'tests':0,'passed':0},'authoredTests':{'state':'NOT_PROVIDED' if arm=='PLAN' else 'NOT_REQUIRED_BY_RAW_INPUT'}}
        if compiled.returncode==0:
            (work/'cases.json').write_text(json.dumps(spec['commonInputsAndExpectedOutputs'],ensure_ascii=False),encoding='utf-8')
            entry='./out/'+binding['entryFile'][:-3]+'.js'
            harness="""const fs=require('node:fs');const mod=require(ENTRY);const fn=mod[EXPORT];
if(typeof fn!=='function'){console.log(JSON.stringify({adapter:'UNSUPPORTED_EXPORT',tests:0,passed:0}));process.exit(2);}
const cases=JSON.parse(fs.readFileSync('cases.json','utf8'));const rows=cases.map(([input,expected],i)=>{
try{const actual=fn(input);return{index:i+1,state:actual===expected?'PASS':'FAIL',expected,actual};}
catch(e){return{index:i+1,state:'FAIL',failure:e.name};}});
console.log(JSON.stringify({tests:rows.length,passed:rows.filter(r=>r.state==='PASS').length,rows}));
process.exitCode=rows.some(r=>r.state==='FAIL')?1:0;""".replace('ENTRY',json.dumps(entry)).replace('EXPORT',json.dumps(binding['entryExport']))
            (work/'independent.cjs').write_text(harness,encoding='utf-8')
            checked=run([node,'independent.cjs'],work,work/'common-tests.log')
            row['independent']=json.loads(checked.stdout)
            row['commonContractState']='ADAPTER_UNSUPPORTED' if checked.returncode==2 else 'PASS' if checked.returncode==0 else 'FAIL'
            tests=[item['name'] for item in binding['files'] if item['kind']=='test']
            if tests:
                authored=run([node,'--test',*['out/'+name[:-3]+'.js' for name in tests]],work,work/'authored-tests.log')
                row['authoredTests']={'state':'PASS' if authored.returncode==0 else 'FAIL','exitCode':authored.returncode}
                for field in ['tests','pass','fail','skipped']:
                    match=re.search(r'^# '+field+r' (\d+)\s*$',authored.stdout,re.M)
                    row['authoredTests'][field]=int(match.group(1)) if match else None
        else: row['commonContractState']='COMPILE_FAILED'
        row['declaredTestCommitmentState']=row['authoredTests']['state'] if arm=='PLAN' else 'NOT_REQUIRED_BY_RAW_INPUT'
        result[arm]=row
    output={'case':'TS-IMPLICIT','criteriaFrozenBeforeReadingWorks':True,'sourceRepaired':False,
            'works':result,'externalModelCalls':0,'oldCredentialAndProfessionalGates':'OPEN_NOT_COVERED'}
    with (LIVE/'hard-checks.json').open('x',encoding='utf-8') as f:json.dump(output,f,ensure_ascii=False,indent=2)
    print(json.dumps({key:{'common':row['commonContractState'],'authored':row['authoredTests']} for key,row in result.items()}))

if __name__=='__main__':main()
