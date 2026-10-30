import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {ensureLogin} from './ensure-live-login.mjs';
const root=process.cwd(),out=path.join(root,'tmp/index-upload-20261030',process.env.DOCUMENT_ACCEPTANCE_RUN||'document-live');await fs.mkdir(out,{recursive:true});
const require=createRequire(path.join(root,'apps/web/package.json'));const {chromium,expect}=require('@playwright/test');
const browser=await chromium.connectOverCDP('http://127.0.0.1:9337');const context=browser.contexts()[0];const page=await context.newPage();
const report={startedAt:new Date().toISOString(),mockedApis:false,modelCalls:0,errors:[],files:[],requests:[],negative:[]};
const sessions=new Map();const save=()=>fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));
const sha=b=>createHash('sha256').update(b).digest('hex');
const base='http://127.0.0.1:5198';let stage='setup';
page.on('pageerror',e=>report.errors.push(e.message));
page.on('response',async response=>{const url=new URL(response.url());if(!url.pathname.startsWith('/api/v1/context/documents'))return;try{const body=await response.json();if(body.data?.documentId){const s=body.data;const previous=sessions.get(s.documentId);sessions.set(s.documentId,{...(previous||{}),status:s,createdAt:previous?.createdAt||Date.now(),lastAt:Date.now(),phases:[...new Set([...(previous?.phases||[]),s.phase])]});report.requests.push({method:response.request().method(),path:url.pathname,status:response.status(),phase:s.phase,uploadedBytes:s.uploadedBytes});}}catch{}});
await page.route(/\/api\/v1\/(?:optimization|enhancement|text-polish)/,route=>{report.modelCalls++;return route.abort()});
const api=async(method,url,body,headers={})=>{
 const token=decodeURIComponent((await context.cookies()).find(c=>c.name==='XSRF-TOKEN')?.value||'');
 const r=await context.request.fetch(base+url,{method,data:body,headers:{'X-XSRF-TOKEN':token,...headers}});let data;try{data=await r.json()}catch{}return {status:r.status(),body:data};
};
try {
 await ensureLogin(context,page,out);
 await page.goto(base+'/workbench');await expect(page.getByRole('button',{name:'添加上下文'})).toBeVisible({timeout:60000});
 await page.evaluate(()=>{window.uploadPercents=[];new MutationObserver(()=>{const node=document.querySelector('.read-progress');if(node){const value=Number(node.getAttribute('aria-valuenow'));if(Number.isFinite(value))window.uploadPercents.push(value)}}).observe(document.body,{subtree:true,attributes:true,childList:true,characterData:true})});
 const fixture=path.join(root,'tmp/index-upload-20261030/fixtures');
 const names=['采购报告.docx','明细表.xlsx','采购附件.pdf','大文件.txt'];
 stage='ui_selection';await page.getByTestId('context-file-input').setInputFiles(names.map(n=>path.join(fixture,n)));
 const confirmation=page.getByRole('dialog',{name:'确认上传需要解析的文档'});await expect(confirmation).toBeVisible();
 report.noUploadBeforeConfirmation=sessions.size===0;await page.screenshot({path:path.join(out,'before-confirmation.png'),fullPage:true});
 await confirmation.getByRole('button',{name:'确认上传所选文档'}).click();
 stage='real_upload_and_parse';let ready=false;
 for(let i=0;i<300;i++){
   const vision=page.getByRole('dialog',{name:'确认图片与 PDF 识别范围'});
   if(await vision.isVisible()){await vision.getByRole('button',{name:'仅本地提取',exact:true}).click();report.pdfVisionConsent=false;}
   const valid=[...sessions.values()].filter(x=>names.includes(path.basename(x.status.path)));
   if(valid.length===4&&valid.every(x=>['READY','PARTIAL','FAILED','CANCELLED'].includes(x.status.phase))){ready=true;break;}
   if(i%10===0){console.log('DOCUMENT_PHASES',valid.map(x=>x.status.phase).join(','));await save();}
   await new Promise(r=>setTimeout(r,1000));
 }
 if(!ready)throw new Error('Documents did not finish in 5 minutes');
 report.uploadPercentSamples=await page.evaluate(()=>[...new Set(window.uploadPercents)]);
 const markers={docx:['WORD_HEAD_QINGYE731','WORD_TAIL_QINGYE731','12345.67','27'],xlsx:['XLSX_HEAD_QINGYE731','XLSX_SHEET2_QINGYE731','XLSX_TAIL_QINGYE731','第二张表'],pdf:['PDF_HEAD_QINGYE731','PDF_TAIL_QINGYE731','12345.67'],txt:['TXT_HEAD_QINGYE731','TXT_TAIL_QINGYE731','五万元']};
 for(const item of sessions.values()){
   const s=item.status;if(!names.includes(path.basename(s.path)))continue;
   const bytes=await fs.readFile(path.join(fixture,path.basename(s.path)));
   const analyzed=await api('POST','/api/v1/context/analyze',{customDescription:'核对合成验收文档的首尾标识、原始金额和末尾业务规则。',files:[{path:s.path,language:s.language,content:'',documentId:s.documentId,sizeBytes:s.fileSizeBytes}]});
   const expected=markers[s.language]||markers[path.extname(s.path).slice(1)]||[];const content=JSON.stringify(analyzed.body?.data?.fileSnippets||[]);
   report.files.push({name:path.basename(s.path),bytes:bytes.length,hash:sha(bytes),documentId:s.documentId,phase:s.phase,uploadedBytes:s.uploadedBytes,extractedCharacters:s.extractedCharacters,chunks:s.chunkCount,warnings:s.warnings,phases:item.phases,elapsedMs:item.lastAt-item.createdAt,analysisStatus:analyzed.status,coverage:analyzed.body?.data?.fileCoverage,returnedMarkers:expected.map(marker=>({marker,present:content.includes(marker)})),pageCoverage:s.pageCoverage});
   await fs.writeFile(path.join(out,`${s.language}-analysis.json`),JSON.stringify(analyzed,null,2));
 }
 await page.getByRole('button',{name:'分析上下文资料',exact:true}).click();const send=page.getByRole('dialog',{name:'确认发送上下文'});if(await send.isVisible())await send.getByRole('button',{name:'确认发送',exact:true}).click();
 await expect(page.getByText('已分析',{exact:true}).first()).toBeVisible({timeout:60000});
 report.uiAnalyzeCompleted=true;await page.screenshot({path:path.join(out,'uploaded-and-analyzed.png'),fullPage:true});
 stage='negative_cases';
 const invalid=await fs.readFile(path.join(fixture,'损坏文档.docx'));
 const created=await api('POST','/api/v1/context/documents',{path:'损坏文档.docx',language:'docx',sizeBytes:invalid.length});const id=created.body.data.documentId;
 await api('PUT',`/api/v1/context/documents/${id}/chunks/0`,invalid,{'Content-Type':'application/octet-stream'});await api('POST',`/api/v1/context/documents/${id}/complete`);
 let invalidStatus;for(let i=0;i<60;i++){invalidStatus=await api('GET',`/api/v1/context/documents/${id}`);if(['FAILED','READY','PARTIAL'].includes(invalidStatus.body?.data?.phase))break;await new Promise(r=>setTimeout(r,500));}
 report.negative.push({case:'corrupted_docx',phase:invalidStatus.body?.data?.phase,errorMessage:invalidStatus.body?.data?.errorMessage});await api('DELETE',`/api/v1/context/documents/${id}`);
 const pending=await api('POST','/api/v1/context/documents',{path:'未传完.txt',language:'txt',sizeBytes:2097152});const pid=pending.body.data.documentId;
 const chunk=Buffer.alloc(1048576,65);const once=await api('PUT',`/api/v1/context/documents/${pid}/chunks/0`,chunk,{'Content-Type':'application/octet-stream'});const repeat=await api('PUT',`/api/v1/context/documents/${pid}/chunks/0`,chunk,{'Content-Type':'application/octet-stream'});
 const incomplete=await api('POST',`/api/v1/context/documents/${pid}/complete`);const deleted=await api('DELETE',`/api/v1/context/documents/${pid}`);const afterDelete=await api('GET',`/api/v1/context/documents/${pid}`);
 report.negative.push({case:'chunk_retry_incomplete_cancel',onceBytes:once.body?.data?.uploadedBytes,repeatedBytes:repeat.body?.data?.uploadedBytes,incompleteCompleteStatus:incomplete.status,deleteStatus:deleted.status,statusAfterDelete:afterDelete.status});
 const oversized=await api('POST','/api/v1/context/documents',{path:'超限.docx',language:'docx',sizeBytes:52428801});report.negative.push({case:'oversized_metadata_only',status:oversized.status,code:oversized.body?.error?.code});
 report.completedAt=new Date().toISOString();await save();
 const independent=JSON.parse(execFileSync(process.execPath,['tmp/index-upload-20261030/check-document-content.mjs'],{encoding:'utf8',env:{...process.env,DOCUMENT_ACCEPTANCE_RUN:path.basename(out)},windowsHide:true}));
 report.independentContent=independent;report.passed=report.noUploadBeforeConfirmation&&report.uiAnalyzeCompleted&&report.modelCalls===0&&report.errors.length===0&&report.files.length===4&&report.files.every(f=>f.phase==='READY'&&f.bytes===f.uploadedBytes&&f.returnedMarkers.length>0&&f.returnedMarkers.every(x=>x.present))&&independent.every(f=>f.found&&f.missingUnits===0&&f.headAndTail.every(x=>x.present))&&report.negative[0].phase==='FAILED'&&report.negative[1].incompleteCompleteStatus===409&&report.negative[1].statusAfterDelete===404;await save();
 console.log('DOCUMENT_ACCEPTANCE_DONE',JSON.stringify({passed:report.passed,files:report.files,negative:report.negative,independentContent:independent,errors:report.errors}));
 // 保留临时合成索引供独立完整性检查，随后由收尾脚本通过业务 DELETE 清理。
}catch(e){report.failure={stage,message:e.message};console.log('DOCUMENT_ACCEPTANCE_FAILURE',stage,e.message);await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});}
finally{await save();await page.close();process.exit(report.failure?1:0);}
