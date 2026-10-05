/** 原生 PowerPoint 与离线 HTML 共用布局；不改业务代码，也不调用模型。 */
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const deps=process.env.PRESENTATION_NODE_MODULES||'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const PptxGenJS=require(path.join(deps,'pptxgenjs'));
const {slides,chapters,sources,verifiedAt,projectName}=require('./content.cjs');
const drawEnhanced=require('./enhanced-layout.cjs');
const OUT=path.resolve(__dirname,'..'), ROOT=path.resolve(OUT,'../../../..');
const W=13.333333333,H=7.5,SCALE=96;
const C={blue:'4d6bfe',purple:'8a5cf6',ink:'1c2541',body:'3a4763',sub:'5b6b8c',muted:'6b7a99',dim:'8a97b5',faint:'98a4c0',green:'0e9f6e',orange:'c2690a',info:'1d5fd6',pink:'c2256f',white:'ffffff',blob1:'c3d6fb',blob2:'dcd0fb',blob3:'c9e8f6',shadow:'465aa0'};
const FONT={serif:'Noto Serif SC',sans:'Noto Sans SC',mono:'Consolas'};
const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const model=[];
let items,serial=0;
function add(e){e.name=e.name||`shape-${++serial}`;items.push(e);return e;}
function t(text,x,y,w,h,size=14,color=C.body,o={}){if(typeof text!=='string')throw Error('Text element requires a string');return add({kind:'text',text,x,y,w,h,size,color,font:'sans',weight:400,lh:1.8,...o});}
function box(x,y,w,h,o={}){return add({kind:'box',x,y,w,h,fill:C.white,transparency:38,stroke:C.white,strokeTransparency:15,radius:.13,shadow:true,...o});}
function pill(text,x,y,w,color=C.blue,o={}){box(x,y,w,.35,{fill:color,transparency:90,stroke:color,strokeTransparency:72,radius:.17,shadow:false,...o});t(text,x+.10,y+.035,w-.20,.27,11,color,{weight:500,lh:1.1,align:'center',...o});}
function line(x1,y1,x2,y2,o={}){return add({kind:'line',x:x1,y:y1,w:x2-x1,h:y2-y1,color:C.blob1,width:1.2,...o});}
function dot(text,x,y,size=.42,color=C.blue){add({kind:'ellipse',x,y,w:size,h:size,fill:color,transparency:0});t(text,x,y+.06,size,size-.06,12,C.white,{font:'mono',lh:1,align:'center',weight:400});}
function icon(name,x,y,size=.48){
 box(x,y,size,size,{gradient:true,fill:C.blue,transparency:0,strokeTransparency:100,radius:.09,shadow:false});
 const col=C.white,th=1.4;
 const l=(a,b,c,d)=>line(x+a*size,y+b*size,x+c*size,y+d*size,{color:col,width:th});
 if(name==='doc'){box(x+size*.26,y+size*.16,size*.48,size*.68,{fill:col,transparency:100,stroke:col,strokeTransparency:0,radius:.02,shadow:false});[.36,.5,.64].forEach(v=>l(.37,v,.62,v));}
 else if(name==='code'){l(.35,.24,.14,.5);l(.14,.5,.35,.76);l(.65,.24,.86,.5);l(.86,.5,.65,.76);l(.57,.2,.43,.8);}
 else if(name==='check'){l(.24,.5,.44,.69);l(.44,.69,.78,.3);}
 else if(name==='grid'){[.25,.58].forEach(a=>[.25,.58].forEach(b=>box(x+a*size,y+b*size,.17*size,.17*size,{fill:col,transparency:0,strokeTransparency:100,radius:0,shadow:false})));}
 else if(name==='people'){add({kind:'ellipse',x:x+.36*size,y:y+.2*size,w:.27*size,h:.27*size,fill:col,transparency:0});l(.24,.76,.33,.56);l(.33,.56,.67,.56);l(.67,.56,.76,.76);}
 else {t(name,x,y+.12,size,.28,13,col,{font:'mono',lh:1,align:'center'});}
}
function smallCard(x,y,w,h,title,body,iconName='doc',o={}){
 box(x,y,w,h);icon(iconName,x+.28,y+.30);t(title,x+.28,y+1.0,w-.56,.37,15,C.ink,{weight:700,lh:1.2});t(body,x+.28,y+1.58,w-.56,h-1.80,12,C.muted,{lh:1.7,...o});
}
function sample(x=10.84,y=1.90,w=1.95){pill('示意样例',x,y,w,C.orange);}
function base(s,i){
 items=[];serial=0;
 add({kind:'ellipse',name:'blob-top',x:8.5,y:-1.35,w:5.6,h:4.8,fill:C.blob1,transparency:45,soft:65});
 if(!['cover','workbench','closing'].includes(s.id))add({kind:'ellipse',name:'blob-bottom',x:-1.30,y:4.65,w:4.8,h:4.4,fill:i%2?C.blob3:C.blob2,transparency:48,soft:65});
 if(s.id==='closing'){items[0]={...items[0],x:9.1,y:4.3,w:4.8,h:4.8,fill:C.blob3};}
 pill(`SLIDE ${String(i+1).padStart(2,'0')} · ${s.tag}`,.50,.43,Math.max(2.85,(11+s.tag.length)*.14+.10),C.blue,{tracking:2});
 if(s.illustrative)sample(10.87,.43,1.92);
 t(`${String(i+1).padStart(2,'0')} / 17`,11.78,6.99,1.03,.23,12,C.dim,{font:'mono',lh:1,align:'right'});
 if(!['cover','closing'].includes(s.id)){
   t(s.title,.50,1.07,12.22,.68,32,C.ink,{font:'serif',weight:900,lh:1.05});
   t(s.subtitle,.53,1.91,12.03,.43,14,C.sub,{lh:1.4});
   t(s.chapter?`CHAPTER ${String(s.chapter).padStart(2,'0')}`:'PROMPT OPTIMIZER PLATFORM',.52,7.02,9.6,.20,10,C.dim,{font:'mono',lh:1});
 }
 return items;
}

function draw(s,i){base(s,i);
 if(!drawEnhanced(s,{t,box,pill,line,dot,icon,add,C,OUT}))switch(s.id){
 case 'cover':
   t(s.title,.65,1.64,8.80,1.80,44,C.blue,{font:'serif',weight:900,lh:1.28,gradient:true});
   t(s.subtitle,.68,3.75,9.8,.43,18,C.sub,{lh:1.2});
   t('把需求与材料，整理成可审阅、可复制的优化提示词。',.68,4.42,8.95,.86,18,C.body,{lh:1.7});
   pill('个人开发者项目',.70,5.72,2.16,C.info);
   t('MVP 本地联调与内部试用',.70,6.35,8.3,.30,14,C.muted,{lh:1.1});
   box(10.13,2.16,2.55,1.12,{transparency:48});t('一个想法',10.36,2.42,2.12,.40,18,C.sub,{font:'serif',weight:900,lh:1.1});
   line(11.30,3.44,11.30,3.83,{arrow:true,color:C.blue,width:1.5});
   box(9.98,4.02,2.71,1.50);icon('doc',10.22,4.27,.46);t('清晰任务',10.22,4.98,2.20,.33,18,C.ink,{font:'serif',weight:900,lh:1.1});
   t('2026.10  /  17 SLIDES  /  16:9',.70,7.01,9,.22,11,C.dim,{font:'mono',lh:1});break;
 case 'agenda':{
   const cols=[.55,4.78,9.01],labels=['理解价值','走进产品','看清边界'];
   cols.forEach((x,c)=>{box(x,2.67,3.78,3.88);t(labels[c],x+.28,2.98,3.2,.42,18,C.ink,{font:'serif',weight:900,lh:1.1});
     for(let r=0;r<3;r++){const j=c*3+r;dot(String(j+1).padStart(2,'0'),x+.28,3.72+r*.83,.36);t(chapters[j],x+.80,3.71+r*.83,2.72,.60,13,C.body,{lh:1.4,go:[2,3,4,5,7,9,11,13,15][j]});}
   });break;}
 case 'position':
   [['输入','原始提示词 + 按需提供的材料'],['增强','补足表达、组织约束、保留未决信息'],['交付','审阅、编辑、复制优化提示词']].forEach((a,j)=>{dot(String(j+1),.65,2.76+j*1.05,.41);t(a[0],1.26,2.77+j*1.05,1.1,.35,15,C.ink,{weight:700,lh:1.1});t(a[1],1.26,3.22+j*1.05,5.1,.42,14,C.body,{lh:1.4});});
   box(7.28,2.55,5.50,4.12,{gradient:true,transparency:0,strokeTransparency:100});
   box(7.75,3.0,4.54,.80,{transparency:8,shadow:false});t('一个想法 + 相关材料',8.0,3.22,4.03,.35,17,C.ink,{align:'center',lh:1.1});
   line(10.04,3.94,10.04,4.28,{arrow:true,color:C.white,width:1.5});
   box(7.75,4.49,4.54,1.35,{transparency:8,shadow:false});t('优化提示词',8.00,4.72,4.04,.44,24,C.ink,{font:'serif',weight:900,align:'center',lh:1.1});t('可检查 · 可编辑 · 可复制',8.00,5.29,4.04,.27,12,C.sub,{align:'center',lh:1.1});
   t('后续任务由你选择的模型或工具执行。',.64,6.34,6.15,.33,13,C.info,{lh:1.3});break;
 case 'pain':
   [['目标模糊','“做一下”还不够明确','说明对象、交付\n与完成条件'],['背景缺席','缺少材料，反复补充','将本次材料\n变成可引用的依据'],['边界遗漏','看似完整，却难以使用','保留限制条件\n与待确认事项']].forEach((a,j)=>{
     const x=.55+j*4.22;box(x,2.69,3.79,3.35);pill(a[0],x+.28,3.03,1.43,C.orange);t(a[1],x+.28,3.76,3.22,.65,15,C.ink,{weight:700,lh:1.5});t(a[2],x+.28,4.74,3.22,.89,14,C.body,{lh:1.8});
   });t('价值方向：减少重复解释，便于发现偏差和复用任务说明。',.64,6.38,11.95,.38,14,C.sub,{lh:1.4});break;
 case 'audience':{
   const cards=[['开发','功能、排错、重构\n明确范围与行为规则','“给工单增加升级功能”','code'],['写作','受众、渠道、语气\n保留资料中的事实','“写一篇活动预告”','doc'],['学习与研究','知识范围、材料来源\n方法条件与未决信息','“制定复习计划”','grid'],['办公','已确认决议与交付\n负责人、期限和格式','“整理会议行动清单”','people']];
   cards.forEach((a,j)=>{const x=.56+j*3.13;box(x,2.72,2.81,3.43);icon(a[3],x+.25,3.03);t(a[0],x+.25,3.79,2.31,.37,15,C.ink,{weight:700,lh:1.15});t(a[1],x+.25,4.40,2.31,.83,12,C.muted,{lh:1.7});t(a[2],x+.25,5.46,2.31,.38,12,C.info,{lh:1.35});});
   pill('示意样例',.63,6.50,1.49,C.orange);t('共享当前工作台；尚未为各领域提供专用编辑器。',2.36,6.50,10.2,.35,13,C.sub,{lh:1.4});break;}
 case 'enhance':
   [['写下需求','目标、用途、材料可以从一句话开始。'],['点击增强','默认直接生成；需要关键决定时开启 Plan。'],['审阅后使用','编辑、复制，或以当前结果再次增强。']].forEach((a,j)=>{dot(String(j+1),.63,2.77+j*1.12,.42);t(a[0],1.28,2.78+j*1.12,5.44,.38,15,C.ink,{weight:700,lh:1.1});t(a[1],1.28,3.27+j*1.12,5.44,.45,14,C.body,{lh:1.5});});
   box(7.29,2.59,5.48,4.11,{gradient:true,transparency:0,strokeTransparency:100});
   box(7.66,2.92,4.74,1.22,{transparency:8,shadow:false});t('根据活动资料，写一篇\n面向初学者的公众号预告。',7.92,3.13,4.22,.81,15,C.ink,{lh:1.7});
   box(8.63,4.37,2.77,.53,{fill:C.white,transparency:0,strokeTransparency:100,radius:.25,shadow:false});t('直接增强提示词',8.71,4.50,2.60,.31,14,C.blue,{weight:700,align:'center',lh:1});
   box(7.66,5.16,4.74,1.14,{transparency:8,shadow:false});t('受众 · 任务 · 篇幅 · 事实边界',7.93,5.38,4.20,.35,14,C.ink,{align:'center',lh:1.2});t('从表达意图，走向可核对的要求',7.93,5.93,4.20,.24,12,C.sub,{align:'center',lh:1});
   sample(.64,6.46,1.5);break;
 case 'structure':{
   box(.54,2.67,12.25,2.31);const titles=['背景','任务','输出','约束'],desc=['依据什么材料\n与已有事实','具体要\n完成什么','需要怎样的\n交付形式','必须保留的\n限制与边界'];
   titles.forEach((a,j)=>{const x=.79+j*3.02;box(x,2.95,2.68,1.73,{transparency:50,strokeTransparency:35,shadow:false,radius:.09});t(a,x+.21,3.19,2.25,.40,22,C.ink,{font:'serif',weight:900,lh:1.1});t(desc[j],x+.21,3.86,2.25,.61,12,C.muted,{lh:1.7});});
   [['验收标准','当前会补齐'],['示例','按开关添加，默认关闭'],['待确认事项','有未决信息时保留']].forEach((a,j)=>{const x=.64+j*4.22;pill(a[0],x,5.53,j===2?1.96:1.51,C.info);t(a[1],x,6.13,3.91,.41,14,C.body,{lh:1.4});});break;}
 case 'planflow':{
   const x0=.82,gap=2.47;['开启 Plan','看已有信息','按需提问','用户确认','最终生成'].forEach((a,j)=>{let x=x0+j*gap;if(j<4)line(x+.42,3.03,x+gap-.19,3.03,{arrow:true,color:C.blue,width:1.5});dot(String(j+1).padStart(2,'0'),x,2.82,.44);t(a,x-.08,3.48,2.18,.42,15,C.ink,{weight:700,lh:1.1});});
   const b=['默认关闭\n首次开启有说明','摘要与相关证据\n减少重复追问','最多 8 题\n单选 / 多选 / 自由回答','有效作答后点击\n“生成最终提示词”','带答案再检索\n核对后生成结果'];b.forEach((a,j)=>t(a,x0-.08+j*gap,4.14,2.13,.97,12,C.muted,{lh:1.7}));
   box(.63,5.66,12.07,.99,{transparency:48});t('没有需要提问的内容时，直接进入最终生成。',.94,5.91,11.48,.49,14,C.info,{lh:1.5});break;}
 case 'plananswer':
   [['主动选择','推荐不会自动成为已确认答案。'],['可以修改','回看并修改；允许时填写自定义回答。'],['保留未知','暂不确定或新冲突，仍作为待确认条件。']].forEach((a,j)=>{t(a[0],.65,2.83+j*1.16,5.90,.40,18,C.ink,{font:'serif',weight:900,lh:1.1});t(a[1],.65,3.36+j*1.16,5.95,.43,14,C.body,{lh:1.5});});
   box(7.13,2.63,5.59,4.13);pill('示意样例',7.48,2.95,1.52,C.orange);t('是否允许越级升级？',7.48,3.53,4.86,.46,20,C.ink,{font:'serif',weight:900,lh:1.1});
   [['A','仅逐级升级'],['B','允许越级升级'],['C','暂不确定']].forEach((a,j)=>{const y=4.20+j*.53;box(7.47,y,4.90,.41,{transparency:j===0?89:50,fill:j===0?C.blue:C.white,stroke:j===0?C.blue:C.white,strokeTransparency:j===0?60:35,radius:.08,shadow:false});t(`${a[0]}   ${a[1]}`,7.67,y+.08,4.52,.29,12,j===0?C.blue:C.body,{lh:1});});
   t('已选',11.48,4.30,.69,.24,11,C.blue,{weight:500,lh:1});
   t('返回修改',7.54,6.14,1.62,.34,12,C.sub,{lh:1.1});box(10.00,6.05,2.36,.47,{fill:C.blue,transparency:0,strokeTransparency:100,radius:.20,shadow:false});t('生成最终提示词',10.11,6.17,2.14,.26,12,C.white,{align:'center',lh:1});break;
 case 'context':
   box(.56,2.70,5.95,2.74);box(6.84,2.70,5.95,2.74);
   icon('code',.91,3.04);t('代码项目',1.66,3.11,4.52,.45,20,C.ink,{font:'serif',weight:900,lh:1.1});
   t('用户选择目录 → 浏览器本地索引\n围绕需求检索有限相关片段',.92,3.97,5.18,.97,14,C.body,{lh:1.8});
   icon('doc',7.19,3.04);t('文档材料',7.94,3.11,4.52,.45,20,C.ink,{font:'serif',weight:900,lh:1.1});
   t('确认上传 → 后端提取与临时索引\n按任务选取文本片段',7.20,3.97,5.18,.97,14,C.body,{lh:1.8});
   line(3.53,5.61,3.53,5.97,{arrow:true,color:C.blue});line(9.81,5.61,9.81,5.97,{arrow:true,color:C.blue});
   box(2.51,6.11,8.33,.56,{transparency:30});t('本次相关片段  +  覆盖报告与警告',2.83,6.25,7.68,.29,14,C.info,{align:'center',lh:1});break;
 case 'stacks':
   smallCard(.56,2.68,3.80,3.40,'技术栈线索','Java / Spring Boot\nNode.js / Vue / React\nPython / Go / Rust 等','code');
   smallCard(4.78,2.68,3.80,3.40,'工程依据','依赖与配置\n目录结构\n相关代码片段','grid');
   smallCard(9.00,2.68,3.80,3.40,'文档文本','PDF 文字层\nWord / Excel\nPowerPoint','doc');
   pill('当前边界',.64,6.49,1.50,C.orange);t('无图片语义识别、扫描 PDF OCR 或自动代码执行。',2.40,6.50,10.20,.37,13,C.sub,{lh:1.4});break;
 case 'workbench':
   box(1.50,2.40,10.33,4.38,{transparency:38});
   add({kind:'image',x:1.66,y:2.54,w:10.00,h:10.00*632/1540,src:'assets/workbench-sample.png',alt:'当前工作台三栏界面，人工示意数据，全部业务请求已隔离，未调用真实模型。'});
   // Captions align with their actual panel, without painting over the product screenshot.
   [['01','上下文',1.96],['02','原始提示词',5.13],['03','审阅结果',9.16]].forEach(a=>{t(a[0],a[2],6.84,.38,.26,12,C.blue,{font:'mono',lh:1});t(a[1],a[2]+.48,6.84,1.72,.26,12,C.sub,{lh:1});});break;
 case 'example':
   box(.58,2.67,4.03,3.99);pill('原始提示词',.88,3.01,1.98,C.info);t('根据活动资料，写一篇\n面向初学者的公众号预告，\n约 300 字，语气亲切。',.88,3.76,3.43,1.69,16,C.body,{lh:1.8});t('材料：用户提供的活动资料',.89,5.80,3.45,.40,12,C.muted,{lh:1.4});
   line(4.83,4.61,5.30,4.61,{arrow:true,color:C.blue,width:1.6});
   box(5.53,2.67,7.22,3.99);const pts=[['背景','依据活动资料，面向初学者。'],['任务与输出','提炼主题与参与方式，约 300 字公众号预告，语气亲切。'],['约束','仅使用可核实事实，不编造时间、费用或讲师经历。'],['发布前确认','报名方式缺失时，先补充并核对。']];
   pts.forEach((a,j)=>{t(a[0],5.86,3.06+j*.80,1.49,.41,13,j===3?C.orange:C.blue,{weight:700,lh:1.3});t(a[1],7.53,3.06+j*.80,4.87,.58,13,C.body,{lh:1.5});});break;
 case 'architecture':
   box(.60,2.64,8.89,.75);t('浏览器工作台',.89,2.81,2.15,.34,15,C.ink,{weight:700,lh:1.1});t('Vue 3 · TypeScript · Element Plus · Pinia · Worker',3.19,2.88,6.00,.30,12,C.sub,{lh:1});
   line(5.00,3.46,5.00,3.81,{arrow:true,color:C.blue});t('/api',5.25,3.48,1.30,.27,11,C.muted,{font:'mono',lh:1});
   box(.60,3.95,8.89,1.54);t('Java 21 + Spring Boot 3  ·  模块化单体',.90,4.14,8.22,.29,14,C.ink,{weight:700,lh:1.1});
   const modules=[['enhancement','增强编排'],['context','上下文'],['policy','权限红线'],['provider','模型调用'],['history','优化记录'],['identity','身份范围']];
   modules.forEach((a,j)=>{const x=.89+j*1.41;box(x,4.62,1.30,.58,{transparency:50,strokeTransparency:45,shadow:false,radius:.09});t(a[0],x+.05,4.73,1.20,.18,10,C.blue,{font:'mono',align:'center',lh:1});t(a[1],x+.04,4.98,1.22,.21,12,C.sub,{align:'center',lh:1});});
   line(2.77,5.62,2.77,5.93,{arrow:true,color:C.blue,width:1.5});line(7.21,5.62,7.21,5.93,{arrow:true,color:C.blue,width:1.5});
   box(.60,6.07,4.26,.56);t('PostgreSQL 16  ·  业务记录',.87,6.23,3.72,.23,12,C.body,{lh:1.1});box(5.18,6.07,4.31,.56);t('Redis 7  ·  短时状态 / 并发',5.44,6.23,3.78,.23,12,C.body,{lh:1.1});
   box(10.11,2.64,2.60,3.99);icon('doc',10.45,2.99);t('模型接入',10.45,3.85,1.92,.42,18,C.ink,{font:'serif',weight:900,lh:1.1});t('Mock\nOpenAI 兼容路由',10.45,4.48,1.97,.72,12,C.sub,{lh:1.7});t('密钥留在服务端\n用户选择已发布模型',10.45,5.54,1.97,.73,12,C.body,{lh:1.7});
   line(9.54,4.72,10.04,4.72,{arrow:true,color:C.blue});break;
 case 'request':{
   const a=[['选择材料','检索本次相关片段','减少无关背景'],['校验身份与规则','安全过滤、上下文分析','明确本次访问与材料范围'],['调用模型','任务策略、并发与超时','限制资源占用'],['校验并保存结果','结构检查、规则检查、历史','结果更容易核对与回看']];
   a.forEach((v,j)=>{const x=.60+j*3.16;box(x,2.68,2.75,2.62);dot(String(j+1).padStart(2,'0'),x+.23,2.99,.39);t(v[0],x+.23,3.59,2.31,.42,15,C.ink,{weight:700,lh:1.2});t(v[1],x+.23,4.19,2.29,.60,12,C.muted,{lh:1.6});t(v[2],x+.23,4.85,2.28,.40,12,C.info,{lh:1.3});if(j<3)line(x+2.84,3.91,x+3.07,3.91,{arrow:true,color:C.blue});});
   box(.60,5.77,12.20,.93,{transparency:48});pill('Plan 前置分支',.88,6.03,2.27,C.info);t('准备摘要 → 提问 → 用户确认 → 再检索 → 最终生成',3.44,6.04,9.01,.38,14,C.body,{lh:1.2});break;}
 case 'boundary':{
   const xs=[.56,4.79,9.02];
   [['已实现的保护',C.green,'默认受保护路径\n敏感内容检测或脱敏\n服务端身份与范围校验'],['使用时的边界',C.info,'材料由用户主动提供\n相关内容经后端发送给供应商\n平台只生成优化提示词'],['尚未开放 / 规划',C.orange,'扫描 PDF OCR、图片语义\n浏览器 / IDE 插件\n团队协作、额度计费']].forEach((v,j)=>{box(xs[j],2.70,3.77,3.22);pill(v[0],xs[j]+.27,3.02,j===2?2.85:2.34,v[1]);t(v[2],xs[j]+.27,3.84,3.23,1.59,13,C.body,{lh:1.8});});
   t('当前阶段：MVP 本地联调与内部试用',.66,6.23,11.99,.35,14,C.ink,{weight:700,lh:1.2});t('正式上线仍需拟开放模型真实业务验收、双人评审和目标部署验收。',.66,6.72,11.90,.32,12,C.sub,{lh:1.25});break;}
 case 'closing':
   t(s.title,1.14,1.77,11.05,1.70,44,C.blue,{font:'serif',weight:900,lh:1.28,gradient:true,align:'center'});
   t('从一个脱敏的小任务开始。',1.10,3.93,11.10,.44,18,C.sub,{align:'center',lh:1.2});
   box(4.61,4.77,4.10,.65,{fill:C.blue,transparency:0,strokeTransparency:100,radius:.32});t('回看示意案例',4.81,4.95,3.70,.36,16,C.white,{weight:500,align:'center',lh:1,go:12});
   t('反馈：哪里更清楚，哪里仍需要解释？',1.20,5.89,10.94,.40,14,C.body,{align:'center',lh:1.4});
   t('个人开发者项目 · MVP 本地联调与内部试用',1.20,6.46,10.94,.33,12,C.muted,{align:'center',lh:1.2});
   t('Copyright © 2026 QingNiao0x  ·  PolyForm Noncommercial 1.0.0',.53,7.00,10.83,.23,10,C.dim,{font:'mono',lh:1});break;
 }
 model.push({...s,index:i+1,elements:items});
}
slides.forEach(draw);
function notes(s){return `第 ${s.index} 页｜${s.title.replace(/\n/g,'，')}\n${s.chapter?'章节 '+s.chapter+'：'+chapters[s.chapter-1]:'封面 / 导览 / 结尾'}\n建议时长：${s.seconds} 秒\n\n核心文案\n${s.copy.join('\n')}\n\n演讲备注\n${s.speech}\n\n演示动作建议\n${s.demo}\n\n视觉与版式\n${s.visual}\n\n事实依据（核对日期 ${verifiedAt}）\n${s.sources.map(k=>`${sources[k].path}\n定位：${sources[k].anchor}\n说明：${sources[k].reason}`).join('\n\n')}\n\n材料属性：宣传讲解材料；人工案例不是客户成果，不将测试结果表述为商业效果。`;}
function shadow(){return{type:'outer',color:C.shadow,opacity:.16,blur:40,offset:16,angle:90};}
async function makePpt(){
 const p=new PptxGenJS();p.defineLayout({name:'PROMO',width:W,height:H});p.layout='PROMO';
 p.author='QingNiao0x';p.subject='个人开发者项目 · MVP 本地联调与内部试用';p.title=projectName+'｜用户介绍';p.company='';p.lang='zh-CN';
 p.theme={headFontFace:FONT.serif,bodyFontFace:FONT.sans,lang:'zh-CN'};
 p.defineSlideMaster({title:'GLASS_LIGHT',background:{color:'e8f0fb'},objects:[]});
 for(const s of model){const slide=p.addSlide({masterName:'GLASS_LIGHT'});slide.addNotes(notes(s));
   for(const e of s.elements){
     const base={x:e.x,y:e.y,w:e.w,h:e.h,objectName:e.name};
     if(e.kind==='text')slide.addText(e.text,{...base,fontFace:e.font==='sans'&&e.weight===500?'Noto Sans SC Medium':FONT[e.font],fontSize:e.size,color:e.color,bold:e.weight>=700,margin:0,breakLine:false,valign:'top',align:e.align||'left',lineSpacingMultiple:e.lh,paraSpaceAfterPt:0,charSpacing:e.tracking||0,lang:'zh-CN',isTextBox:true,...(e.go!==undefined?{hyperlink:{slide:e.go+1}}:{})});
     else if(e.kind==='box')slide.addShape(e.radius?'roundRect':'rect',{...base,rectRadius:e.radius,fill:{color:e.fill,transparency:e.transparency},line:{color:e.stroke||C.white,transparency:e.strokeTransparency??100,width:1},...(e.shadow?{shadow:shadow()}:{})});
     else if(e.kind==='ellipse')slide.addShape('ellipse',{...base,fill:{color:e.fill,transparency:e.transparency},line:{transparency:100}});
     // DrawingML 不能使用负尺寸；保留线段方向，避免向上的图标笔画被压成水平线。
     else if(e.kind==='line')slide.addShape('line',{...base,x:Math.min(e.x,e.x+e.w),y:Math.min(e.y,e.y+e.h),w:Math.abs(e.w),h:Math.abs(e.h),flipH:e.w<0,flipV:e.h<0,line:{color:e.color,width:e.width,...(e.arrow?{endArrowType:'triangle'}:{})}});
     else if(e.kind==='image')slide.addImage({...base,path:path.join(OUT,e.src),altText:e.alt});
   }
 }
 await p.writeFile({fileName:path.join(OUT,'Prompt-Optimizer-用户介绍.pptx')});
}
const px=n=>(n*SCALE).toFixed(2)+'px';
function renderElem(e){
 const pos=`left:${px(e.x)};top:${px(e.y)};width:${px(e.w)};height:${px(e.h)};`;
 const attrs=` data-name="${e.name}"`;
 if(e.kind==='text'){const style=pos+`font-family:var(--${e.font});font-size:${e.size*4/3}px;font-weight:${e.weight};line-height:${e.lh};color:#${e.color};text-align:${e.align||'left'};letter-spacing:${(e.tracking||0)*4/3}px;`;
   const inner=escape(e.text).replace(/\n/g,'<br>');return e.go!==undefined?`<button class="el txt text-button ${e.gradient?'gradient-text':''}"${attrs} style="${style}" data-go="${e.go}">${inner}</button>`:`<div class="el txt ${e.gradient?'gradient-text':''}"${attrs} style="${style}">${inner}</div>`;
 }
 if(e.kind==='box'||e.kind==='ellipse'){const fill=e.gradient?`linear-gradient(135deg,#${C.blue},#${C.purple})`:`rgba(${parseInt(e.fill.slice(0,2),16)},${parseInt(e.fill.slice(2,4),16)},${parseInt(e.fill.slice(4,6),16)},${(100-e.transparency)/100})`;
   return `<div class="el ${e.kind==='box'?'glass':'blob'}"${attrs} aria-hidden="true" style="${pos}background:${fill};border-radius:${e.kind==='ellipse'?'50%':px(e.radius||0)};${e.kind==='box'?`border:1.333px solid #${e.stroke||C.white}${Math.round((100-(e.strokeTransparency??100))*2.55).toString(16).padStart(2,'0')};`:''}${e.shadow?`box-shadow:0 21.33px 53.33px #${C.shadow}29;`:''}${e.soft?`filter:blur(${e.soft*4/3}px);`:''}"></div>`;
 }
 if(e.kind==='line'){const x1=0,y1=0,x2=e.w*SCALE,y2=e.h*SCALE;const ww=Math.max(2,Math.abs(x2)),hh=Math.max(2,Math.abs(y2));return `<svg class="el connector"${attrs} aria-hidden="true" style="left:${px(e.x)};top:${px(e.y)};width:${ww}px;height:${hh}px;overflow:visible" viewBox="0 0 ${ww} ${hh}"><defs><marker id="${e.name}" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0 0 L6 3 L0 6Z" fill="#${e.color}"/></marker></defs><line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#${e.color}" stroke-width="${e.width*4/3}" ${e.arrow?`marker-end="url(#${e.name})"`:''}/></svg>`;}
 if(e.kind==='image')return `<img class="el screenshot"${attrs} style="${pos}" alt="${escape(e.alt)}" src="data:image/png;base64,${fs.readFileSync(path.join(OUT,e.src)).toString('base64')}">`;
}
function makeHtml(){
 const styles=fs.readFileSync(path.join(__dirname,'presenter.css'),'utf8');
 const app=fs.readFileSync(path.join(__dirname,'presenter.js'),'utf8');
 const fonts=[['sans','NotoSansSC-400.woff2',400],['sans','NotoSansSC-500.woff2',500],['sans','NotoSansSC-700.woff2',700],['serif','NotoSerifSC-900.woff2',900]].map(([name,file,weight])=>`@font-face{font-family:"PO ${name}";src:url(data:font/woff2;base64,${fs.readFileSync(path.join(OUT,'assets/fonts',file)).toString('base64')}) format('woff2');font-weight:${weight};font-style:normal;font-display:block}`).join('\n');
 const fontLicense=['OFL-NotoSansSC.txt','OFL-NotoSerifSC.txt'].map(f=>fs.readFileSync(path.join(OUT,'assets/fonts',f),'utf8')).join('\n\n');
 const html=`<!doctype html><!-- Embedded Noto font subsets: copyright is also preserved in font metadata.\n${fontLicense.replace(/--/g,'—')}\n--><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>Prompt Optimizer Platform · 用户介绍</title><style>${fonts}\n${styles}</style></head><body>
 <a class="skip" href="#controls">跳转到放映控制</a>
 <main id="presenter" aria-label="Prompt Optimizer Platform 用户介绍幻灯片">
 <div id="stage-shell"><div id="stage">${model.map((s,i)=>`<section class="slide ${i===0?'active':''}" aria-label="第 ${i+1} 页 ${escape(s.title.replace(/\n/g,' '))}" data-page="${i}" ${i===0?'':'inert'}>${s.elements.map(e=>renderElem({...e,name:`p${s.index}-${e.name}`})).join('')}</section>`).join('')}</div></div>
 <div class="progress" aria-hidden="true"><div id="progress-bar"></div></div>
 <aside class="mobile-guide" aria-label="手机阅读提示">建议横屏查看幻灯片，或<button id="mobile-read">打开讲稿阅读</button></aside>
 <nav id="controls" aria-label="放映控制"><div class="control-left"><button id="overview-button" title="目录（O）" aria-haspopup="dialog">九章目录</button><span id="current-chapter"></span></div><div class="pager"><button id="prev" aria-label="上一页">←</button><output id="counter" aria-live="polite">01 / 17</output><button id="next" aria-label="下一页">→</button></div><div class="control-right"><button id="notes-button" aria-expanded="false" aria-controls="notes-panel" title="讲稿与来源（N）">讲稿与来源</button><button id="fullscreen" title="全屏（F）">全屏</button></div></nav>
 <p class="keyhint">← → 翻页 <span>·</span> O 目录 <span>·</span> N 讲稿 <span>·</span> F 全屏 <span>·</span> 手机可左右滑动</p>
 </main>
 <aside id="notes-panel" hidden aria-label="本页讲稿与事实来源"><div class="notes-head"><div><span class="eyebrow">PRESENTER NOTES</span><h2 id="notes-title"></h2></div><button id="close-notes" aria-label="关闭讲稿">×</button></div><div id="notes-content"></div></aside>
 <dialog id="overview"><div class="dialog-head"><div><span class="eyebrow">NINE CHAPTERS</span><h2>选择章节，继续介绍</h2></div><button id="close-overview" aria-label="关闭目录">×</button></div><div class="chapter-grid">${chapters.map((c,i)=>`<button data-go="${[2,3,4,5,7,9,11,13,15][i]}"><span>${String(i+1).padStart(2,'0')}</span>${escape(c)}</button>`).join('')}</div><div class="quick-links"><button data-go="0">封面</button><button data-go="1">目录页</button><button data-go="16">结尾与反馈</button></div></dialog>
 <div id="toast" role="status" hidden></div>
 <script id="deck-data" type="application/json">${JSON.stringify(model.map(({elements,...s})=>({...s,sourceDetails:s.sources.map(k=>sources[k])}))).replace(/</g,'\\u003c')}</script><script>${app}</script>
 </body></html>`;
 fs.writeFileSync(path.join(OUT,'Prompt-Optimizer-用户介绍.html'),html);
}
function makeScript(){
 let md=`# Prompt Optimizer Platform｜用户宣传介绍逐页脚本\n\n核对日期：${verifiedAt}。个人开发者项目；当前阶段：**MVP 本地联调与内部试用**。共 17 页、九章，建议讲解约 ${Math.round(slides.reduce((n,s)=>n+s.seconds,0)/60)} 分钟；现场操作另留 3–5 分钟。\n\n## 目录大纲\n\n| 章节 | 页码 | 核心问题 |\n| --- | --- | --- |\n`;
 const pages=['03','04','05','06–07','08–09','10–11','12–13','14–15','16'];const qs=['这是什么产品、交付什么？','为什么先明确需求有价值？','谁能用、适合什么任务？','怎样开始、结果是什么结构？','重要决定怎样由用户确认？','资料怎样参与、多技术栈指什么？','真实界面怎样操作、怎样核对示例？','技术设计怎样帮助用户？','当前能力、数据流和未开放范围是什么？'];
 chapters.forEach((c,i)=>{md+=`| ${String(i+1).padStart(2,'0')} ${c} | ${pages[i]} | ${qs[i]} |\n`;});
 md+='\n封面、目录与结尾不计入九章；已删除“当前进展与未来规划”章节。未开放事项仅在第 16 页独立标注，不作为当前能力或带日期的路线图。技术部分仅第 14 页架构图、第 15 页请求流程图。\n\n## 统一视觉规范\n\n- 16:9，13.333 × 7.5 英寸；HTML 使用 1280 × 720 画布等比呈现。物理尺寸换算为 960 × 540 pt；设计文件中的 1024 × 576 pt 仅视为比例参考，避免尺寸单位冲突。\n- 全册浅蓝 Glassmorphism；背景 135° 三段渐变 #e8f0fb / #f7faff / #eef3fd。\n- 主强调 #4d6bfe；#8a5cf6 只参与蓝紫渐变；标题 #1c2541；正文 #3a4763 / #5b6b8c；说明 #6b7a99 / #8a97b5 / #98a4c0。\n- 标题 Noto Serif SC 900；正文 Noto Sans SC 400/500/700；数字参数 Consolas。封面/结尾 44pt、普通标题 32pt、正文 14pt、卡片 12–15pt、标签 11pt、参数 10–12pt。\n- 外层玻璃白色 38% 透明，白边 1pt / 15% 透明，圆角约 0.13 英寸；内层白色 50% 透明、约 0.09 英寸圆角。外阴影 #465aa0 / 16% 不透明，40pt 模糊、16pt 向下。\n- 色球最多两个，置于内容下方，颜色取规范淡蓝/淡紫/淡青，45–48% 透明。标签左上、页码右下；主要内容约 0.5 英寸边距。\n- PPT 文字、图表式卡片、流程线和架构图均为原生可编辑对象；第 8、9、12 页的实际界面截图作为图片素材，不是整页贴图。HTML 离线内嵌字体与截图。新增讲解页右上标注“示意样例”，不作为真实客户成果。\n\n## 逐页脚本\n\n';
 for(const s of model){md+=`### ${String(s.index).padStart(2,'0')}｜${s.title.replace(/\n/g,'，')}\n\n**归属**：${s.chapter?String(s.chapter).padStart(2,'0')+' '+chapters[s.chapter-1]:'封面 / 导览 / 结尾'}；**建议时长**：${s.seconds} 秒。\n\n**页面标题**：${s.title.replace(/\n/g,' / ')}\n\n**副标题**：${s.subtitle}\n\n**核心文案**\n\n${s.copy.map(c=>'- '+c).join('\n')}\n\n**演讲备注**\n\n${s.speech}\n\n**演示动作建议**\n\n${s.demo}\n\n**视觉元素与版式**\n\n${s.visual}\n\n**事实依据**\n\n${s.sources.map(k=>{const r=sources[k];return `- \`${r.path}\` — ${r.anchor}。${r.reason}`}).join('\n')}\n\n`;}
 md+='## 核对与使用约束\n\n1. 演示数据、截图结果、问答题目均为人工示意；不代表客户成果、实测速度、真实模型质量或领域验收。\n2. 没有添加公司背景、商业资质、客户规模、节省比例、Token 降幅、生产 SLA 或公开访问地址。\n3. 已实现和未开放范围分别表达；不将自动代码执行、OCR、图片语义、插件、计费或团队协作写成当前能力。\n4. 真实模型的生成内容需要人工复核；保护规则不等于识别所有敏感信息，逻辑删除不等于即刻物理擦除。\n5. 设计文件与产品范围草案中的固定段落、模型选择和历史保存说明，按当前代码修正；桌面素材中直接生成代码或报告的表述已移除。详见 source/素材采用与事实核对.md；界面截图不保留任何真实账户数据。\n6. README 推荐技术栈中的 JWT 为可选项，讲解不把它写成当前认证机制；当前使用服务端会话身份与 CSRF 保护。\n7. 宣讲前确认目标投影与放映软件的字体、换行、透明效果；HTML 可作为离线演示备份。\n8. 原有版权为 Copyright © 2026 QingNiao0x，项目使用 PolyForm Noncommercial License 1.0.0；未经作者书面许可，禁止商业使用。\n';
 fs.writeFileSync(path.join(OUT,'逐页脚本与大纲.md'),md);
 const trace=Object.entries(sources).map(([id,r])=>{const f=path.isAbsolute(r.path)?r.path:path.join(ROOT,r.path);return{id,...r,exists:fs.existsSync(f),sha256:fs.existsSync(f)?crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex'):null};});
 if(trace.some(x=>!x.exists))throw Error('Missing source: '+trace.filter(x=>!x.exists).map(x=>x.path).join(', '));
 fs.writeFileSync(path.join(OUT,'source','sources-snapshot.json'),JSON.stringify({verifiedAt,sources:trace},null,2));
 fs.writeFileSync(path.join(OUT,'source','layout.json'),JSON.stringify(model,null,2));
 fs.writeFileSync(path.join(OUT,'source','notes.txt'),model.map(notes).join('\n\n==============\n\n'));
}
(async()=>{makeScript();if(!process.argv.includes('--metadata-only')){await makePpt();makeHtml();}console.log(JSON.stringify({slides:model.length,chapters:chapters.length,output:OUT,mode:process.argv.includes('--metadata-only')?'metadata':'all'}));})().catch(e=>{console.error(e);process.exit(1)});
