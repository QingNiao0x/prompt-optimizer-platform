// 对真实HTML作品做离线浏览器行为检查；不修改作品，不调用模型或访问外网。
import { readFile, writeFile, readdir, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
const require=createRequire(resolve('apps/web/package.json'));
const {chromium}=require('@playwright/test');
const root=resolve('docs/testing/evidence/natural-phase-compatibility-2026-10-22');
const directory=resolve('tmp/natural-phase-compatibility-20261022/html');
await mkdir(directory,{recursive:true});
const browser=await chromium.launch({channel:'msedge',headless:true});
const samples=[
  {name:'given_spaces_tabs',input:'  alpha  \n\n \t \nbeta\t\n',expected:'  alpha  \nbeta\t'},
  {name:'given_blank_only',input:'\n \t\n\t\n',expected:''},
  {name:'given_crlf',input:'x\r\n\r\n y ',expected:'x\n y '},
  {name:'empty',input:'',expected:''},
  {name:'nonempty_characters',input:'  中文 <tag> & "引号" \t\n\n 0 \n A\tB ',expected:'  中文 <tag> & "引号" \t\n 0 \n A\tB '}
];
const rows=[];
try {
  for(const name of (await readdir(resolve(root,'works'))).filter(x=>x.startsWith('HOLDOUT_TOOL_')&&x.endsWith('.md')).sort()) {
    const content=await readFile(resolve(root,'works',name),'utf8');
    const match=content.match(/```(?:html)?\s*\n([\s\S]*?)```/i);
    const html=match?match[1]:content;
    const complete=/<html[\s>]/i.test(html)&&/<\/html>/i.test(html)&&/<!doctype\s+html>/i.test(html);
    await writeFile(resolve(directory,name+'.html'),html,{flag:'wx'});
    const context=await browser.newContext({offline:true});
    const requests=[],errors=[];
    await context.route('**/*',route=>{requests.push(route.request().url());return route.abort();});
    const page=await context.newPage();
    page.on('pageerror',error=>errors.push(error.message));
    // 所有持久化尝试记录后拒绝；验证处理期间没有写入，不用网络隔离伪装作品本身无上传代码。
    await page.addInitScript(()=>{
      window.storageAttempts=[];
      Storage.prototype.setItem=function(){window.storageAttempts.push('Storage.setItem');};
    });
    await page.goto('about:blank');
    await page.setContent(html,{waitUntil:'domcontentloaded'});
    const checks=[];
    for(const sample of samples){
      await page.locator('#inputText').fill(sample.input);
      if(await page.locator('#cleanBtn').count()) await page.locator('#cleanBtn').click();
      else if(await page.locator('#processBtn').count()) await page.locator('#processBtn').click();
      const actual=await page.locator('#outputText').inputValue();
      checks.push({name:sample.name,passed:actual===sample.expected,actual,expected:sample.expected});
    }
    const storageAttempts=await page.evaluate(()=>window.storageAttempts??[]);
    const staticRiskPatterns=[/\bfetch\s*\(/,/XMLHttpRequest|WebSocket|sendBeacon/,/\b(?:localStorage|sessionStorage|indexedDB)\s*[.(]/,/document\.cookie\s*=/,/<(?:script|link|iframe)[^>]*(?:src|href)\s*=\s*["']https?:/i];
    const staticRisks=staticRiskPatterns.map((p,i)=>p.test(html)?i:null).filter(x=>x!==null);
    rows.push({artifact:name,completeHtml:complete,checks,requests,errors,storageAttempts,staticRisks,
      passed:complete&&checks.every(x=>x.passed)&&!requests.length&&!errors.length&&!storageAttempts.length&&!staticRisks.length});
    await context.close();
  }
}finally{await browser.close();}
await writeFile(resolve(root,'html-work-checks.json'),JSON.stringify({browser:'Playwright installed Edge headless offline',checksPerArtifact:samples.length,rows,
 scope:'只验证本批24实际返回的1份作品。未验收所有浏览器、剪贴板权限或未约定的Unicode空白定义。'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({artifacts:rows.length,passed:rows.filter(x=>x.passed).length,behaviorChecks:rows.reduce((n,x)=>n+x.checks.length,0)}));
