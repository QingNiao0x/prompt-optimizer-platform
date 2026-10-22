"""只读复算本批真实调用、同条件作品与严格格式；不调用模型或修改评分。"""
from pathlib import Path
import json,sys,re,ast
from collections import Counter
folder=Path(__file__).resolve().parent
runtime=folder.parents[3]/"tmp/natural-completion-preparation-20261022"
if "--runtime" in sys.argv:
    traces=[json.loads(p.read_text(encoding="utf-8")) for p in sorted((runtime/"traces").glob("*.json")) if p.stat().st_size]
    results=[json.loads(p.read_text(encoding="utf-8")) for p in sorted((runtime/"results").glob("*.json"))]
else:
    traces=[json.loads(line) for line in (folder/"transports.jsonl").read_text(encoding="utf-8").splitlines() if line]
    results=json.loads((folder/"http-results.json").read_text(encoding="utf-8"))
protocol=json.loads((folder/"protocol.json").read_text(encoding="utf-8"))
counts=Counter(t["phase"] for t in traces)
assert len(traces)<=protocol["maximumNewActualCalls"]
assert all(n<=protocol["phaseLimits"][phase] for phase,n in counts.items())
assert all(t["jobId"] in protocol["allowedJobs"] for t in traces)
by_job=Counter(t["jobId"] for t in traces)
assert all(n<=4 if "_V15_" in job and "_GEN" in job else n<=2 if "_EXEC" not in job else n==1 for job,n in by_job.items())
metrics={}
for version in ("4","15"):
    selected=[t for t in traces if t["phase"]=="GENERATION" and "_V"+version+"_" in t["jobId"]]
    metrics[version]={"attempts":len(selected),"tokens":sum(t.get("response",{}).get("usage",{}).get("total_tokens",0) for t in selected),"inputTokens":sum(t.get("response",{}).get("usage",{}).get("prompt_tokens",0) for t in selected),"systemCharacters":sorted(set(len(t["request"]["messages"][0]["content"]) for t in selected))}
checks=[]
for r in results:
    if r["phase"]!="EXECUTION" or r["status"]!=200: continue
    text=r["body"]["data"]["content"]; item={"id":r["id"],"finish":r["body"]["data"]["finishReason"],"characters":len(text),"nonWhitespaceCharacters":len(re.sub(r"\s", "",text))}
    if r["caseId"]=="SUFFICIENT_TSV": item["exactTsv"]=text=="周二\t9\n周一\t4\n周三\t0"
    if r["caseId"]=="SUFFICIENT_REMINDER": item["within45"]=item["nonWhitespaceCharacters"]<=45
    if r["caseId"]=="REFUND_REPLY": item["within200"]=item["nonWhitespaceCharacters"]<=200
    if r["caseId"]=="LIBRARY_RENEWAL": item["within180"]=item["nonWhitespaceCharacters"]<=180
    if r["caseId"]=="SUFFICIENT_TABLE":
        rows=[line.strip() for line in text.splitlines()]
        cells=lambda line:[cell.strip() for cell in line.strip("|").split("|")]
        item["exactTable"]=len(rows)==4 and cells(rows[0])==["区域","数量"] and cells(rows[2])==["北区","12"] and cells(rows[3])==["南区","0"] and all(re.fullmatch(r":?-{3,}:?",c) for c in cells(rows[1])) and len(cells(rows[1]))==2
    if r["caseId"]=="SUFFICIENT_FUNCTION":
        tree=ast.parse(text)
        # 只运行已检查的纯取模比较函数，不允许调用、导入、属性访问、默认参数或其它语句。
        allowed=(ast.Module,ast.FunctionDef,ast.arguments,ast.arg,ast.Return,ast.Compare,ast.BinOp,ast.Name,ast.Load,ast.Constant,ast.Mod,ast.Eq)
        assert all(isinstance(node,allowed) for node in ast.walk(tree))
        assert len(tree.body)==1 and isinstance(tree.body[0],ast.FunctionDef) and tree.body[0].name=="is_even"
        assert not tree.body[0].decorator_list and not tree.body[0].args.defaults
        assert all(node.id=="n" for node in ast.walk(tree) if isinstance(node,ast.Name))
        space={"__builtins__":{}}
        exec(compile(tree,"<synthetic-is-even>","exec"),space)
        inputs=[-1000001,-100,-3,-2,-1,0,1,2,3,100,1000001]
        item["integerBehavior"]=all(type(space["is_even"](n)) is bool and space["is_even"](n)==(n%2==0) for n in inputs)
        item["behaviorCases"]=len(inputs)
    if r["caseId"]=="CONTROL_LINEBREAKS": item["exactText"]=text=="甲=07\n乙=00"
    if r["caseId"]=="CONTROL_SORTED_JSON":
        try: item["exactJson"]=json.loads(text)==[-2,0,3,3] and isinstance(json.loads(text),list)
        except ValueError: item["exactJson"]=False
    checks.append(item)
result={"actualCalls":len(traces),"phaseCounts":dict(counts),"protocolRetries":sum(n-1 for n in Counter((t["jobId"],t.get("generationStage","OTHER")) for t in traces).values()),"qualityRetries":0,"tokens":sum(t.get("response",{}).get("usage",{}).get("total_tokens",0) for t in traces),"generationCost":metrics,"artifactChecks":checks}
print(json.dumps(result,ensure_ascii=False,indent=2))
