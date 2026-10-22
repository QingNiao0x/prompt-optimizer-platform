"""只读复算本批真实调用、同条件作品与严格格式；不调用模型或修改评分。"""
from pathlib import Path
import json,sys,re
from collections import Counter
folder=Path(__file__).resolve().parent
runtime=folder.parents[3]/"tmp/natural-concise-completion-20261022"
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
assert all(n<=2 if "_EXEC" not in job else n==1 for job,n in by_job.items())
metrics={}
for version in ("4","13"):
    selected=[t for t in traces if t["phase"]=="GENERATION" and "_V"+version+"_" in t["jobId"]]
    metrics[version]={"attempts":len(selected),"tokens":sum(t.get("response",{}).get("usage",{}).get("total_tokens",0) for t in selected),"inputTokens":sum(t.get("response",{}).get("usage",{}).get("prompt_tokens",0) for t in selected),"systemCharacters":sorted(set(len(t["request"]["messages"][0]["content"]) for t in selected))}
checks=[]
for r in results:
    if r["phase"]!="EXECUTION" or r["status"]!=200: continue
    text=r["body"]["data"]["content"]; item={"id":r["id"],"finish":r["body"]["data"]["finishReason"],"characters":len(text),"nonWhitespaceCharacters":len(re.sub(r"\s", "",text))}
    if r["caseId"]=="SUFFICIENT_TSV": item["exactTsv"]=text=="周二\t9\n周一\t4\n周三\t0"
    if r["caseId"]=="SUFFICIENT_REMINDER": item["within45"]=item["nonWhitespaceCharacters"]<=45
    if r["caseId"]=="REFUND_REPLY": item["within200"]=item["nonWhitespaceCharacters"]<=200
    checks.append(item)
result={"actualCalls":len(traces),"phaseCounts":dict(counts),"protocolRetries":sum(n-1 for n in by_job.values()),"qualityRetries":0,"tokens":sum(t.get("response",{}).get("usage",{}).get("total_tokens",0) for t in traces),"generationCost":metrics,"artifactChecks":checks}
print(json.dumps(result,ensure_ascii=False,indent=2))
