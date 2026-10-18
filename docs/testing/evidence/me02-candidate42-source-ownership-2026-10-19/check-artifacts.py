"""Execute unchanged model artifacts with synthetic HTTP responses and no application credentials."""
from pathlib import Path
import hashlib, json, re, subprocess, os, sys

ROOT = Path(__file__).resolve().parents[4]
LIVE = ROOT / 'tmp/me02-candidate42r2-20261019'
PUBLIC = Path(__file__).resolve().parent
SHA = lambda value: hashlib.sha256(value).hexdigest()
BINDINGS = {
    'REQUESTS_RAW': {'implementation': 1, 'tests': None, 'module': 'title_raw', 'entry': 'get_page_title', 'cli': None},
    'REQUESTS_COMPACT': {'implementation': 1, 'tests': 4, 'module': 'fetch_titles', 'entry': 'fetch_titles', 'cli': None},
    'REQUESTS_STRUCTURED': {'implementation': 1, 'tests': 2, 'module': 'fetch_titles', 'entry': 'fetch_title', 'cli': 'main'},
}

DRIVER = r'''
import contextlib,importlib,io,json,sys,unittest
from pathlib import Path
from unittest.mock import patch
import requests

binding=json.loads(Path('binding.json').read_text('utf-8'))
secret='SYNTHETIC_ONLY_CANDIDATE42_7f93'
url='https://example.invalid/page?token='+secret
def network_blocked(*a,**k):raise RuntimeError('External network is disabled in artifact verification')
requests.sessions.Session.request=network_blocked
module=importlib.import_module(binding['module']);fn=getattr(module,binding['entry'])
def response(target,status=200,body='<title>Hello</title>'):
    r=requests.Response();r.status_code=status;r.url=target;r._content=body.encode('utf-8');r.encoding='utf-8'
    r.headers['Content-Type']='text/html; charset=utf-8';return r
def trial(kind,target='https://example.invalid/page',cli=False):
    def get(u,*a,**k):
        if kind=='timeout':raise requests.exceptions.Timeout('timeout for '+u)
        if kind=='connection':raise requests.exceptions.ConnectionError('connection failed for '+u)
        if not isinstance(u,str) or not u.startswith(('http://','https://')):raise requests.exceptions.InvalidSchema('invalid URL '+str(u))
        return response(u,404 if kind=='http404' else 200,
            '<title>A &amp; B</title>' if kind=='entity' else '<body>no title</body>' if kind=='missing' else '<title>Hello</title>')
    output=io.StringIO();errors=io.StringIO();value=None;chain=[]
    with contextlib.redirect_stdout(output),contextlib.redirect_stderr(errors),patch.object(module.requests,'get',side_effect=get):
        try:value=getattr(module,binding['cli'])([target]) if cli else fn(target)
        except Exception as error:
            seen=set();queue=[error]
            while queue and len(seen)<12:
                item=queue.pop(0)
                if item is None or id(item) in seen:continue
                seen.add(id(item));chain.append(str(item));queue += [item.__cause__,item.__context__]
    return {'value':value,'stdout':output.getvalue(),'stderr':errors.getvalue(),'exceptionChain':chain}
def title(row):
    value=row['value']
    if isinstance(value,list) and value and isinstance(value[0],dict):return value[0].get('title') if value[0].get('ok') else None
    return value
common=[]
for kind,expected in [('normal','Hello'),('entity','A & B')]:
    row=trial(kind);common.append({'name':kind,'passed':title(row)==expected,'observed':row})
for kind in ['missing','timeout','connection','http404']:
    row=trial(kind);value=title(row);common.append({'name':kind,'passed':value not in ('Hello','A & B'),'observed':row})
security=[]
for kind in ['normal','timeout','connection','http404','invalid']:
    row=trial(kind,'ftp://example.invalid/?token='+secret if kind=='invalid' else url)
    security.append({'name':kind,'passed':secret not in repr(row),'observed':row})
if binding['cli']:
    row=trial('normal',url,True);security.append({'name':'cli-success','passed':secret not in repr(row),'observed':row})
authors={'state':'NOT_PROVIDED','notRequiredByOriginal':binding['tests'] is None}
if binding['tests'] is not None:
    suite=unittest.defaultTestLoader.discover('.',pattern='test_*.py');stream=io.StringIO()
    result=unittest.TextTestRunner(stream=stream,verbosity=2).run(suite)
    Path('authored-tests.log').write_text(stream.getvalue(),'utf-8')
    authors={'state':'PASS' if result.wasSuccessful() else 'FAIL','tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)}
Path('check.json').write_text(json.dumps({'common':common,'security':security,'authoredTests':authors,'realNetworkAccess':False,'implementationRepaired':False},ensure_ascii=False,indent=2)+'\n','utf-8')
'''


def run(command, directory):
    """Child artifacts receive only OS runtime variables; HTTP is intercepted before importing them."""
    allowed = {'SYSTEMROOT','WINDIR','PATH','TEMP','TMP','PATHEXT','COMSPEC','PYTHONIOENCODING','PYTHONUTF8'}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env['PYTHONIOENCODING'] = 'utf-8'
    return subprocess.run(command, cwd=directory, env=env, capture_output=True, text=True,
                          encoding='utf-8', errors='replace', timeout=45)


def main():
    """Bindings are explicit and hashed; unsupported layouts fail instead of being silently rewritten."""
    result = {'sourceCandidate':'candidate42r2','code':{},'text':{},'reviewScoresUsedForRelease':False}
    artifacts = {}
    for name, binding in BINDINGS.items():
        folder = LIVE/'works'/name
        text = (folder/'work.md').read_text('utf-8')
        blocks = list(re.finditer(r'^```([^\n]*)\n(.*?)^```', text, re.M|re.S))
        target = folder/'independent-check'
        target.mkdir(exist_ok=False)
        extracted = []
        for index, block in enumerate(blocks):
            if block.group(1).strip().lower() not in ('python','py'): continue
            code = block.group(2)
            if re.search(r'\b(?:subprocess|os\.environ|shutil\.rmtree|eval|exec)\b',code):
                raise ValueError('Generated code needs additional execution-scope review')
            filename = binding['module']+'.py' if index==binding['implementation'] else 'test_'+binding['module']+'.py' if index==binding['tests'] else f'unexecuted_snippet_{index}.py'
            (target/filename).write_text(code,encoding='utf-8',newline='')
            extracted.append({'block':index,'file':filename,'sha256':SHA(code.encode()),'role':'implementation' if index==binding['implementation'] else 'test' if index==binding['tests'] else 'syntax-only alternative/usage'})
        (target/'binding.json').write_text(json.dumps(binding),encoding='utf-8')
        files=[row['file'] for row in extracted]
        compiled=run([sys.executable,'-m','py_compile',*files],target)
        (target/'compile.log').write_text(compiled.stdout+compiled.stderr,'utf-8')
        row={'jointCompileExitCode':compiled.returncode,'workSha256':SHA(text.encode()),'files':extracted,'scope':'合成HTTP响应；实现与作者测试均未修改；不访问真实网站。'}
        if compiled.returncode==0:
            (target/'verify_generated.py').write_text(DRIVER,'utf-8')
            checked=run([sys.executable,'verify_generated.py'],target)
            (target/'check-run.log').write_text(checked.stdout+checked.stderr,'utf-8')
            row['checkerExitCode']=checked.returncode
            if checked.returncode==0: row.update(json.loads((target/'check.json').read_text('utf-8')))
        row['executionClaims']=[{'quote':m.group(),'status':'REVIEW_REQUIRED'} for m in re.finditer(r'[^\n]*(?:已(?:经)?(?:实际)?(?:运行|执行|编译|测试)|测试通过|全部.{0,8}通过)[^\n]*',text)]
        artifacts[name]={'workSha256':row['workSha256'],'binding':binding,'files':extracted}
        result['code'][name]=row
    for name in ['REFUND_RAW','REFUND_PLAN']:
        text=(LIVE/'works'/name/'work.md').read_text('utf-8')
        result['text'][name]={'workSha256':SHA(text.encode()),'hanCharacters':len(re.findall(r'[\u3400-\u4dbf\u4e00-\u9fff]',text)),
            'preservesEventTime':'2026年11月8日14:00' in text,'fullRefund':'全额退款' in text,
            'noRescheduling':'不安排改期' in text or '不再改期' in text,
            'unsupportedRefundChannel':'原支付渠道' in text or '原付款渠道' in text,
            'unconfirmedSignatureDate':bool(re.search(r'^2026年11月8日\s*$',text,re.M))}
    text=(LIVE/'works/JSON_STRUCTURED/work.md').read_text('utf-8')
    try: result['text']['JSON_STRUCTURED']={'strictJson':json.loads(text)=={'title':'城市绿化观察'},'workSha256':SHA(text.encode())}
    except json.JSONDecodeError:result['text']['JSON_STRUCTURED']={'strictJson':False,'workSha256':SHA(text.encode())}
    (LIVE/'work-artifact-bindings.json').write_text(json.dumps(artifacts,ensure_ascii=False,indent=2)+'\n','utf-8')
    (LIVE/'hard-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps({'code':{name:{'jointCompileExitCode':row['jointCompileExitCode'],'commonPassed':sum(x['passed'] for x in row.get('common',[])),'commonTotal':len(row.get('common',[])),'securityFailed':sum(not x['passed'] for x in row.get('security',[])),'authoredTests':row.get('authoredTests'),'checkerExitCode':row.get('checkerExitCode')} for name,row in result['code'].items()},'text':result['text']},ensure_ascii=False))

if __name__=='__main__': main()
