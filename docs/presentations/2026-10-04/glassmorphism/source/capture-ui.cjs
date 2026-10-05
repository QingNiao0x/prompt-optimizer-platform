/** 实际 Vue 界面、隔离接口和人工样例；不访问真实模型或业务数据。 */
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require(path.join(process.cwd(),'apps/web/node_modules/playwright'));
const OUT=path.join(process.cwd(),'docs/presentations/2026-10-04/glassmorphism/assets');
const user={userId:'00000000-0000-0000-0000-000000000102',tenantId:'00000000-0000-0000-0000-000000000101',workspaceId:'00000000-0000-0000-0000-000000000103',email:'sample@example.invalid',displayName:'示意演示',platformAdmin:false};
const sections=[
 {type:'BACKGROUND',title:'背景',content:'依据用户提供的活动资料，为初学者准备公众号预告。'},
 {type:'TASK',title:'任务',content:'提炼活动主题、适合人群和参与方式。'},
 {type:'OUTPUT',title:'输出',content:'约 300 字，语气亲切，适合公众号发布。'},
 {type:'CONSTRAINTS',title:'约束',content:'仅使用资料中可核实的事实；不编造讲师经历、时间或费用。报名信息缺失时先确认。'},
 {type:'ACCEPTANCE',title:'验收标准',content:'受众、篇幅与语气符合要求，关键事实可回到材料核对。'}
];
const report={customDescription:'示意样例：活动面向初学者，主题是学习如何清晰表达 AI 需求；报名方式待补充。',technologyStack:[],dependencies:[],directoryTree:[],fileSnippets:[],warnings:[],redactions:[],analysisVersion:'v3'};
const result={optimizedPrompt:sections.map(s=>'## '+s.title+'\n'+s.content).join('\n\n'),sections,contextReport:report,ambiguities:[],appliedConstraints:[],templateCode:'GENERAL',provider:{provider:'Mock',model:'mock',mock:true},latencyMs:0};
const plan={planId:'demo-plan-illustrative',summary:'示意样例：先确认预告的读者与发布要求，再整理成优化提示词。',templateCode:'GENERAL',questions:[
 {id:'audience',question:'这篇活动预告主要写给谁？',hint:'受众会影响表达方式和需要解释的内容。',type:'SINGLE_CHOICE',allowCustomAnswer:false,examples:[],options:[
  {id:'beginner',label:'初学者',description:'从基本概念讲起，少用专业术语。',answer:'面向初学者，用通俗语言介绍活动。',recommended:true,recommendationReason:'建议项，仍需你主动选择。'},
  {id:'experienced',label:'已有经验的读者',description:'侧重方法与实践细节。',answer:'面向已有经验的读者，侧重方法与实践。',recommended:false}
 ]},
 {id:'format',question:'希望用什么形式和语气发布？',hint:'写下渠道、篇幅和语气；不确定的信息可以明确保留。',type:'FREE_TEXT',allowCustomAnswer:true,examples:['公众号预告，约 300 字，语气亲切'],options:[]}
]};
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true,args:['--disable-gpu']});
 const page=await browser.newPage({viewport:{width:1540,height:1100},deviceScaleFactor:1.5});
 await page.addInitScript(()=>{localStorage.setItem('po-theme','light');localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled:true,introSeen:true}));});
 const requests=[],errors=[],shots=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const u=new URL(route.request().url());requests.push({method:route.request().method(),path:u.pathname});let data=null;
  if(u.pathname.endsWith('/csrf'))data={token:'sample-csrf'};
  else if(u.pathname.startsWith('/api/v1/auth/'))data=user;
  else if(u.pathname.endsWith('/models'))data=[{id:'mock',displayName:'示意演示模型',provider:'Mock',defaultModel:true}];
  else if(u.pathname.endsWith('/analytics/context'))data={userId:user.userId,loginSessionId:null};
  else if(u.pathname.endsWith('/optimizations/plan'))data=plan;
  else if(u.pathname.endsWith('/optimizations'))data=result;
  await route.fulfill({status:200,json:{data}});
 });
 await page.goto('http://127.0.0.1:5196/workbench',{waitUntil:'networkidle'});
 await page.getByLabel('原始提示词',{exact:true}).fill('根据活动资料，帮我写一篇活动预告。');
 console.log('Workbench buttons:',await page.getByRole('button').allTextContents());
 const enhance=page.getByRole('button',{name:'先确认并增强',exact:true}); await enhance.click();
 await page.locator('.plan-question-dialog .question-sheet').waitFor();
 await page.evaluate(()=>document.fonts.ready);
 const capture=async(selector,name)=>{const el=page.locator(selector);await el.screenshot({path:path.join(OUT,name)});shots.push({file:name,selector,size:await el.boundingBox()});};
 await capture('.plan-question-dialog .question-sheet','plan-question-sample.png');
 await page.locator('.plan-question-dialog .answer-option').first().click();
 await page.getByRole('button',{name:'下一题',exact:true}).click();
 await page.getByLabel('填写回答',{exact:true}).fill('公众号预告，约 300 字，语气亲切；报名方式仍待确认。');
 await page.getByRole('button',{name:'核对已填答案（2）',exact:true}).click();
 const firstAnswer=await page.locator('.answer-review li').first().boundingBox();
 const lastAnswer=await page.locator('.answer-review li').last().boundingBox();
 // 捕获列表内容框，不包含浏览器在框外绘制的 ol 序号；不改真实 UI 的文字或样式。
 const clip={x:firstAnswer.x,y:firstAnswer.y,width:firstAnswer.width,height:lastAnswer.y+lastAnswer.height-firstAnswer.y};
 await page.screenshot({path:path.join(OUT,'plan-review-sample.png'),clip});
 shots.push({file:'plan-review-sample.png',selector:'.answer-review li content bounds (outside list markers excluded)',size:clip});
 await capture('.dialog-actions','plan-actions-sample.png');
 // 验证确认按钮确实提交当前人工答案，再截取工作台；接口仍被完全拦截。
 await page.getByRole('button',{name:'生成最终提示词',exact:true}).click();
 await page.getByText('约 300 字，语气亲切，适合公众号发布。',{exact:false}).first().waitFor();
 await page.setViewportSize({width:1540,height:740});
 await capture('.workbench-grid','workbench-sample.png');
 if(errors.length)throw Error(errors.join('\n'));
 fs.writeFileSync(path.join(OUT,'screenshot-provenance.json'),JSON.stringify({capturedAt:new Date().toISOString(),source:['apps/web/src/pages/PromptWorkbenchPage.vue','apps/web/src/components/prompt/PlanQuestionDialog.vue'],url:'http://127.0.0.1:5196/workbench',data:'示意样例。实际 Vue 界面，人工题目、答案和结果；全部 /api 请求被拦截，无真实账户、数据库或 Provider 调用。',screenshots:shots,apiRequests:requests,pageErrors:errors},null,2));
 console.log(JSON.stringify({shots,interceptedRequests:requests.length,pageErrors:errors}));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
