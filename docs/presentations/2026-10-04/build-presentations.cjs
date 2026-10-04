/**
 * 生成两种视觉风格的用户宣讲材料。内容以本次核实的实现为边界，
 * 演示案例为人工编写的讲解样例，所有文字与流程图均保留为可编辑对象。
 * 依赖由环境提供；不修改应用依赖或调用项目的模型服务。
 */
const fs = require('node:fs');
const path = require('node:path');
const deps = process.env.PRESENTATION_NODE_MODULES ||
  'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const PptxGenJS = require(path.join(deps, 'pptxgenjs'));
const OUT = __dirname;
const W = 13.333333, H = 7.5;
const FONT = 'Microsoft YaHei';

const themes = {
  midnight: { id:'midnight', file:'Prompt-Optimizer-项目风格版', label:'项目风格版',
    bg:'0B1120', panel:'12182C', panel2:'19233D', text:'E9EEF8', muted:'A9B6CF',
    dim:'8797B5', line:'2C3957', accent:'6B85FF', secondary:'A78BFA', signal:'38BDF8',
    good:'34D399', warn:'FBBF24', pale:'1A2846', heading:FONT },
  editorial: { id:'editorial', file:'Prompt-Optimizer-自由设计版', label:'自由设计版',
    bg:'F7F3EA', panel:'FFFDFA', panel2:'E6ECE4', text:'173B36', muted:'50665E',
    dim:'65776E', line:'D8DCD0', accent:'C45535', secondary:'286D5C', signal:'286D5C',
    good:'286D5C', warn:'A3502E', pale:'F3E3D6', heading:'SimSun' },
};

const materialSources = {
  product:['README.md', 'CONTEXT.md'],
  workbench:['apps/web/src/pages/PromptWorkbenchPage.vue', 'apps/web/src/components/prompt/ResultPanel.vue'],
  plan:['apps/web/src/components/prompt/PlanQuestionDialog.vue', 'docs/12-内置Plan-Mode交互与接口.md'],
  context:['services/api/src/main/java/com/promptoptimizer/context/service/impl/DefaultContextAnalyzer.java', 'docs/06-上下文分析设计.md'],
  output:['services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/OptimizationResultAssembler.java'],
  security:['AGENTS.md', 'README.md', 'docs/08-安全隐私与商业化基础.md'],
  architecture:['apps/web/package.json', 'services/api/pom.xml', 'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/concurrency/ModelConcurrencyLimiter.java'],
  boundary:['README.md', 'docs/待办事项.md'],
};

const content = {
  cover:{ title:'把一句想法，变成清晰的 AI 任务', minutes:0.6, sources:['product'],
    speech:'大家好，这是我个人开发的 Prompt Optimizer Platform。它关注一个很常见的问题：我们心里有想法，但还没把背景、目标和要求说清楚，就把任务交给了 AI。这个平台帮助我们先把需求整理成一份可以审阅的优化提示词，再交给自己选择的模型或工具使用。今天我会从真实的使用过程出发，介绍它能帮上什么忙，以及当前版本的能力边界。',
    action:'介绍个人开发者身份。说明这是 MVP 试用介绍，不宣称已正式商用或通过所有领域验收。'},
  pain:{ title:'很多返工，从“没说清”开始', minutes:0.65, sources:['product'],
    speech:'一句“帮我写一下”通常隐藏着很多没有说出来的决定。给谁看、依据哪些材料、需要多长、哪些内容不能改变，都会影响结果。如果这些信息缺席，模型容易自行补全，我们就要不断解释和改写。平台要解决的是这段沟通成本：尽早把缺少的说明找出来，让要求变得可以核对。实际节省多少时间，仍需要在自己的任务中检验。',
    action:'用“帮我写份活动预告”开场，邀请听众想一想还有什么信息没交代。不要给出没有实测依据的效率百分比。'},
  value:{ title:'准备得更清楚，沟通就更省力', minutes:0.6, sources:['product'],
    speech:'平台的价值可以从三个使用感受理解。第一，目标和交付要求放在一起，减少反复解释。第二，已有材料成为本次需求的依据，不必每次从头介绍背景。第三，重要限制和待确认事项留在结果中，便于交付前检查。它把需求整理这件事变得有步骤，而实际生成效果仍会受到材料质量和所选模型的影响。',
    action:'强调预期价值和工作方式，不把这些表述当作量化测试结论。'},
  workbench:{ title:'三栏工作台，走完一次增强', minutes:0.9, sources:['workbench','product'],
    speech:'工作台从左到右是上下文、原始提示词和优化结果。左边按需放材料或背景说明，中间写下任务，并选择直接增强还是 Plan 确认，右边审阅结果。清楚的需求可以直接增强，拿不准关键决定时再开启 Plan。图中使用演示数据重绘了这三个区域，目的是让第一次使用的人看懂流程，不代表一次真实模型运行结果。',
    action:'若现场演示，先登录并选用管理员已发布的模型。准备公开或脱敏资料，按当前界面完成上传和发送范围确认。'},
  enhance:{ title:'一次增强，把要求组织完整', minutes:0.85, sources:['product','output'],
    speech:'以活动预告为例，原始提示词是“根据活动资料写一段公众号预告”。用户另外提供了新用户受众、二百字篇幅和三个标题的要求。优化后，这些信息被组织为背景、任务、输出和约束。时间、地点如果材料没有写明，就继续保留为待确认信息。这一页展示的是人工编写的结构节选。平台生成的是给下游模型使用的任务说明，并没有在这里完成文章写作。',
    action:'先读左侧输入与补充要求，再逐块讲右侧结构。明确这些具体要求来自演示输入，没有让模型凭空决定。'},
  context:{ title:'已有材料，成为本次需求的依据', minutes:0.85, sources:['context'],
    speech:'项目上下文不只指代码，也包括项目说明、会议记录、草稿和研究材料。代码目录可以在浏览器建立本地索引，围绕需求选取相关片段。文档支持 PDF 文字层以及常见办公文件的文本提取，大文档可以分片处理。这里有一个重要区别：选中了文件，不等于模型已经看过每一处内容。平台会展示覆盖情况和警告，用户要结合这些提示检查结果。',
    action:'演示添加文件或文件夹，说明上传确认与发送范围。扫描版 PDF OCR、图片内容理解当前不支持。'},
  stack:{ title:'换一个技术栈，也能带上项目背景', minutes:0.65, sources:['context'],
    speech:'开发者可以把配置文件和相关源码一起带进来。当前分析器能够从文件路径、配置和内容识别 Java、Spring Boot、Node.js、Vue、React、TypeScript、Python、Go 和 Rust 等线索，并为支持的配置提取依赖信息。识别出来的技术栈帮助提示词贴近现有项目。这是一种基于证据的项目识别，仍然可能有遗漏，不代表所有框架都已经获得同等深度的语义理解或执行验证。',
    action:'展示技术标签，说明依赖提取主要覆盖 Maven、npm、Python 和 Go 配置。不要称为自动编译或全语言执行环境。'},
  plan:{ title:'关键决定，先问清再生成', minutes:1.0, sources:['plan','output'],
    speech:'Plan Mode 适合范围或要求还没有定下来的任务。平台先结合可用上下文，再生成最多八个需要确认的问题，支持单选、多选和自由输入。推荐答案需要你主动选择，也可以在允许时补充自己的回答。你可以回看和修改，全部回答有效后点击“生成最终提示词”。平台会结合确认信息准备最终上下文并生成结果。信息已经充分时可以跳过提问，未确定的条件也不会因为完成问答就自动变成事实。',
    action:'现场显示一个问题，再回到答案核对页。不要将示意问题描述为所有任务必定出现的问题。'},
  developer:{ title:'开发演示：把工单升级规则说清楚', minutes:0.95, sources:['context','plan','output'],
    speech:'这个人工演示案例来自一个假设的 Vue 和 Spring Boot 工单项目。用户要为超过四十八小时仍未处理的工单增加升级提醒，并提供现有接口与规则说明。需要确认的决定可能包括提醒接收人、是否改变工单状态。用户在这里选择负责人和值班管理员，并明确不自动关闭。最终提示词应保留严格超过四十八小时这一条件，围绕既有权限组织修改范围和测试要求。具体代码仍由后续工具执行。',
    action:'展示材料、用户确认与结果要求的对应关系。此案例是讲解样例，不是已测量的客户项目成果。'},
  scenarios:{ title:'开发之外，同样适用于日常任务', minutes:0.8, sources:['product'],
    speech:'办公、写作、学习和研究也有相同的沟通难题。会议记录要区分已经确认的决议与未定事项，文案需要明确受众和语气，复习计划需要学习范围和可用时间，研究分析要说明数据来源与方法条件。这些场景可以使用同一个工作台来组织提示词。专业内容仍要由具备相应知识的人复核，提供研究方案并不等于已经取得研究结果。',
    action:'选择最贴近现场听众的一张场景卡展开，不必把全部场景逐字念完。'},
  structure:{ title:'四个核心部分，让结果容易核对', minutes:0.65, sources:['output'],
    speech:'优化提示词有四个必须具备的部分：背景、任务、输出和约束。它们分别回答依据是什么、要做什么、交付什么、遵守什么。当前版本还会补充验收标准，示例由开关决定，待确认事项在确有未决条件时出现。内容会随着任务和材料变化，但当前并没有开放任意省略核心结构的自然文模式。审阅时可以按这四个问题逐项核对。',
    action:'不要把当前输出说成固定六段，也不要宣称已经支持自由省略验收段落。'},
  control:{ title:'审阅之后，下一步由你决定', minutes:0.65, sources:['workbench','product'],
    speech:'生成完成后可以编辑、复制，也可以把当前结果作为新输入再次增强。复制并打开目标平台，只帮助你带走提示词，后续仍需自己粘贴和发送。优化历史支持查找、查看详情和重新优化，便于回顾一份好用的需求说明。页面上的手动编辑不会覆盖原历史记录。平台把判断权留给用户，尤其是存在待确认事项时，应当先核实再交付执行。',
    action:'演示编辑和复制。若展示历史，使用专用演示账号及无敏感内容的记录。'},
  privacy:{ title:'你提供材料，也看得见处理边界', minutes:0.85, sources:['security','context'],
    speech:'浏览器处理的是用户主动选择的文件。系统会过滤受保护路径，检测或脱敏可识别的敏感内容，并在结果中写入权限红线。模型调用会发送本次允许的相关上下文，所以仍应只提供自己有权使用的材料。历史记录保留原始提示词、优化提示词和脱敏摘要，不保存上传文件正文。平台只生成提示词，删除文件、改数据库或部署等执行权限仍属于下游工具和用户。',
    action:'区分浏览器本地代码索引、后端临时文档处理与上游模型调用。不要承诺全部离线、绝不上传、绝不保留任何信息或百分之百识别秘密。'},
  architecture:{ title:'技术在后台，帮助流程更稳妥', minutes:0.8, sources:['architecture','context'],
    speech:'技术部分只讲与体验有关的几点。Vue 和浏览器 Worker 承担交互及本地索引，让较重的文件处理尽量不挤占主界面。Java 二十一和 Spring Boot 的模块化服务负责上下文、安全规则、Plan 与结果校验。PostgreSQL 保存业务记录，Redis 支撑短时状态和共享并发控制，模型接口适配 OpenAI 兼容服务。这些是稳定运行的设计基础，实际响应速度仍取决于材料、模型和部署环境。',
    action:'控制在一分钟内，不逐项介绍框架名。不要引用历史局部压测作为生产 SLA 或当前全场景容量承诺。'},
  status:{ title:'核心流程已具备，欢迎申请试用', minutes:0.7, sources:['boundary'],
    speech:'当前版本已经实现工作台、直接增强、Plan 确认、上下文处理和优化历史，并支持邮箱登录及管理员发布的模型选择。它仍处于 MVP 本地联调和内部试用阶段。图片理解和扫描件 OCR 没有开放，团队管理、额度计费和浏览器插件也还需要后续建设。正式上线前还要完成拟开放模型的真实业务验收和部署验收。这里希望邀请大家用自己的任务检验价值，并反馈具体问题。',
    action:'试用范围和入口由开发者实际安排，不展示虚构网址、价格、客户数量或合作背书。'},
  closing:{ title:'带一个真实任务，试一次更清晰的表达', minutes:0.55, sources:['product','boundary'],
    speech:'你可以从手头正在做的一件小事开始：写下目标，按需提供有权使用的材料，选择直接增强或 Plan，最后审阅并复制优化提示词。试完后，我最希望听到两个反馈：哪些要求被整理得更清楚，哪些事实或边界还需要你纠正。这个个人项目会围绕真实使用反馈继续完善。感谢大家，欢迎向我申请试用并交流使用感受。',
    action:'口头说明自己实际可用的联系方式或试用安排。文件中不预填未经确认的网址、二维码和联系方式。'},
};

/** 统一采用英寸定位和显式字号，避免字体自动缩小掩盖过量文字。 */
function text(slide, str, x,y,w,h,size,color,options={}) {
  slide.addText(str,{x,y,w,h,fontSize:size,fontFace:FONT,color,margin:0,
    breakLine:false,vertAnchor:'top',valign:'mid',paraSpaceAfterPt:0,
    lineSpacingMultiple:1.15,...options});
}
function box(slide,t,x,y,w,h,options={}) {
  const type=options.square?'rect':'roundRect';
  slide.addShape(type,{x,y,w,h,rectRadius:0.13,
    fill:{color:options.fill||t.panel,transparency:options.transparency||0},
    line:{color:options.stroke||t.line,width:options.lineWidth||0.8},
    ...options.shape});
}
function line(slide,t,x1,y1,x2,y2,arrow=false,color) {
  slide.addShape('line',{x:x1,y:y1,w:x2-x1,h:y2-y1,
    line:{color:color||t.dim,width:1.25,...(arrow?{endArrowType:'triangle'}:{})}});
}
function badge(slide,t,label,x,y,w,color=t.accent,filled=false) {
  box(slide,t,x,y,w,0.36,{fill:filled?color:t.panel2,stroke:filled?color:t.line});
  text(slide,label,x+0.08,y+0.02,w-0.16,0.30,10.8,filled?'FFFFFF':t.muted,{align:'center'});
}
function circle(slide,t,label,x,y,size=0.46,color=t.accent) {
  slide.addShape('ellipse',{x,y,w:size,h:size,fill:{color},line:{color,transparency:100}});
  text(slide,label,x,y+0.01,size,size-0.02,size*35,'FFFFFF',{bold:true,align:'center'});
}
function icon(slide,t,name,x,y,scale=0.65,color=t.accent) {
  const ox=x,oy=y,sz=scale;
  const seg=(a,b,c,d)=>line(slide,t,ox+a*sz,oy+b*sz,ox+c*sz,oy+d*sz,false,color);
  if(name==='doc') {
    box(slide,t,x+sz*.12,y,sz*.70,sz,{fill:t.panel,stroke:color});
    [0.3,0.48,0.66].forEach(v=>seg(.28,v,.66,v));
  } else if(name==='code') {
    seg(.30,.22,.08,.5);seg(.08,.5,.30,.78);seg(.70,.22,.92,.5);seg(.92,.5,.70,.78);seg(.59,.1,.42,.9);
  } else if(name==='check') {
    slide.addShape('ellipse',{x,y,w:sz,h:sz,fill:{color:t.panel2},line:{color,width:1.5}});
    seg(.22,.50,.42,.70);seg(.42,.70,.77,.27);
  } else if(name==='lock') {
    box(slide,t,x+sz*.1,y+sz*.38,sz*.8,sz*.6,{fill:t.panel2,stroke:color});
    slide.addShape('arc',{x:x+sz*.26,y:y,w:sz*.48,h:sz*.62,rotate:180,fill:{color:t.panel2,transparency:100},line:{color,width:1.5}});
    seg(.50,.60,.50,.80);
  } else {
    circle(slide,t,name,x,y,sz,color);
  }
}

function base(p,t,key,index,total,overrideTitle) {
  const s=p.addSlide();s.background={color:t.bg};
  const title=overrideTitle||content[key].title;
  if(t.id==='midnight') {
    s.addShape('ellipse',{x:10.1,y:0.2,w:2.55,h:2.55,fill:{color:t.accent,transparency:96},line:{transparency:100}});
    text(s,'PROMPT OPTIMIZER PLATFORM',0.64,0.40,9,0.23,10.5,t.dim,{charSpacing:1.8,fontFace:'Segoe UI'});
    text(s,title,0.62,0.90,12.05,0.83,33,t.text,{bold:true,fontFace:t.heading});
  } else {
    text(s,'想法整理室',0.65,0.38,4,0.28,12,t.accent,{bold:true});
    text(s,'Prompt Optimizer Platform',8.8,0.38,3.9,0.28,11,t.muted,{align:'right',fontFace:'Segoe UI'});
    text(s,title,0.62,0.95,11.85,0.88,34,t.text,{bold:true,fontFace:t.heading});
  }
  text(s,'个人开发项目  /  MVP 试用介绍',0.64,7.08,9,0.18,9.2,t.dim);
  text(s,`${String(index).padStart(2,'0')} / ${total}`,11.65,7.06,1.0,0.24,10,t.dim,{align:'right',fontFace:'Segoe UI'});
  return s;
}
function intro(slide,t,str) {text(slide,str,.65,1.84,12.0,.54,16.5,t.muted);}
function caption(slide,t,str,y=6.55){text(slide,str,.66,y,12.0,.34,11.2,t.dim);}
function note(slide,key,t,index) {
  const c=content[key];
  let speech=c.speech;
  if(t.id==='editorial'&&key==='cover') speech='大家好，我是这个项目的开发者。今天先从一个很小的场景开始：我们希望 AI 帮忙写一份活动预告，却没有说清楚给谁看、发在哪里、必须保留什么。Prompt Optimizer Platform 帮助我们把这些要求整理到一起，让发送给 AI 的任务更清楚。接下来会用办公写作和开发案例，介绍如何从一句想法走到一份可审阅的优化提示词。';
  if(t.id==='editorial'&&key==='developer')speech+='同类项目识别还覆盖 React、Python、Go、Rust 等线索，具体识别结果需要用户核对。';
  const sources=[...new Set(c.sources.flatMap(k=>materialSources[k]))];
  const title=t.id==='editorial'?(alternateTitles[key]||'在点击发送之前，先把想法说清楚'):c.title;
  slide.addNotes(`第 ${index} 页｜${title}\n建议时长：约 ${Math.round(c.minutes*60)} 秒\n\n讲稿\n${speech}\n\n演示动作\n${c.action}\n\n事实依据：仓库实现与文档核对，2026-10-04\n${sources.join('\n')}`);
  return {...c,title,speech,index,sources};
}

function cover(p,t,total) {
  const s=p.addSlide();s.background={color:t.bg};
  if(t.id==='midnight') {
    s.addShape('ellipse',{x:8.2,y:0.5,w:4.3,h:4.3,fill:{color:'6B85FF',transparency:92},line:{transparency:100}});
    s.addShape('ellipse',{x:9.3,y:2.4,w:2.8,h:2.8,fill:{color:'A78BFA',transparency:90},line:{transparency:100}});
    badge(s,t,'MVP 用户宣讲',.72,.62,1.80);
    text(s,'把一句想法，\n变成清晰的 AI 任务',.7,1.43,8.6,1.75,40,t.text,{bold:true,breakLine:false});
    text(s,'Prompt Optimizer Platform',.74,3.64,8.5,.50,25,t.accent,{fontFace:'Segoe UI Semibold'});
    text(s,'先组织需求，再交给你选择的模型或工具。',.74,4.46,7.5,.65,20,t.muted);
    const labels=['背景','任务','输出','约束'];
    labels.forEach((label,i)=>{box(s,t,9.5+(i%2)*1.08,1.75+Math.floor(i/2)*1.18,.96,1.02,{fill:i===1?t.accent:t.panel2,stroke:t.line});text(s,label,9.5+(i%2)*1.08,2.08+Math.floor(i/2)*1.18,.96,.35,19,i===1?'FFFFFF':t.text,{align:'center',bold:true});});
    line(s,t,9.55,4.43,11.60,4.43,true,t.secondary);
    text(s,'可审阅的优化提示词',9.2,4.78,3.1,.50,15,t.muted,{align:'center'});
  } else {
    text(s,'想法整理室',.72,.65,6,.4,18,t.accent,{bold:true});
    text(s,'在点击发送之前，\n先把想法说清楚。',.68,1.66,8.8,1.70,44,t.text,{bold:true,fontFace:t.heading});
    text(s,'Prompt Optimizer Platform',.74,3.73,8,.5,24,t.secondary,{fontFace:'Georgia'});
    text(s,'让办公、写作和开发任务，从明确的要求开始。',.75,4.59,8.5,.60,19,t.muted);
    box(s,t,9.0,1.55,3.1,3.88,{fill:'F0E0D1',stroke:'F0E0D1',square:true});
    box(s,t,9.26,1.80,2.90,3.88,{fill:'FFFDFA',stroke:'D9CABB',square:true});
    text(s,'“',9.48,2.00,1.1,.70,62,t.accent,{fontFace:'Georgia'});
    text(s,'一个想法\n一份材料\n一份清楚的\n任务说明',9.59,2.89,2.15,1.75,23,t.text,{fontFace:t.heading,bold:true});
    badge(s,t,'个人开发 · MVP 试用',9.38,5.00,2.70,t.secondary,true);
  }
  text(s,'QingNiao0x  /  个人开发项目',.75,6.30,8,.38,15,t.muted);
  text(s,'产品生成优化提示词，实际任务由用户选择的工具执行。',.75,6.90,11,.3,10.5,t.dim);
  return s;
}

function pain(s,t) {
  if(t.id==='editorial') {
    text(s,'“帮我写份\n活动预告。”',.74,2.5,5.35,1.63,37,t.text,{fontFace:t.heading,bold:true});
    text(s,'一句话里的空白，往往要靠多轮补充。',.78,4.62,5.0,.86,19,t.muted);
    [['给谁看？','受众影响内容取舍'],['依据什么？','材料决定事实边界'],['交付什么？','渠道、篇幅与形式'],['不能改什么？','已有规则需要保留']].forEach((a,i)=>{
      circle(s,t,String(i+1),6.6,2.35+i*1.0,.42,t.accent);
      text(s,a[0],7.25,2.33+i*1.0,4.3,.36,20,t.text,{bold:true});text(s,a[1],7.25,2.76+i*1.0,4.8,.30,14,t.muted);
    });
  } else {
    intro(s,t,'目标、依据、交付和限制没有说清，模型就容易自行补全。');
    const a=[['目标模糊','“处理一下”\n还缺少明确任务','?'],['背景缺席','项目事实散落\n在文件和脑海里','doc'],['交付无标准','长度、格式和验收\n没有统一说明','check'],['边界被遗漏','哪些不能改\n哪些需要确认','lock']];
    a.forEach((v,i)=>{let x=.65+i*3.08;box(s,t,x,2.74,2.80,2.72);icon(s,t,v[2],x+.25,3.02,.55,i%2?t.secondary:t.accent);text(s,v[0],x+.24,3.88,2.3,.45,23,t.text,{bold:true});text(s,v[1],x+.24,4.49,2.32,.70,16,t.muted);});
    caption(s,t,'平台的目标：把这些缺口尽早变成可核对的信息。',6.10);
  }
}
function value(s,t) {
  intro(s,t,'把“反复解释”变成一次有依据、可检查的需求整理。');
  const rows=[['需求整理','反复补充背景','目标与交付要求集中表达'],['资料利用','复制大量内容','围绕当前任务选择相关片段'],['交付检查','凭感觉判断够不够','约束与待确认事项清楚可见']];
  rows.forEach((v,i)=>{let y=2.64+i*1.12;circle(s,t,String(i+1),.72,y+.24,.48);text(s,v[0],1.42,y+.18,2.0,.40,22,t.text,{bold:true});text(s,v[1],4.05,y+.19,3.0,.4,18,t.muted);line(s,t,7.00,y+.41,7.65,y+.41,true);box(s,t,8.03,y,4.55,.88,{fill:t.panel2});text(s,v[2],8.28,y+.22,4.1,.39,18,t.text);});
  caption(s,t,'价值需在实际任务中检验，本材料不承诺固定的时间或费用节省比例。');
}
function workbench(s,t) {
  intro(s,t,'上下文、原始提示词、优化结果，在一个页面里衔接。');
  const ui=themes.midnight;
  box(s,ui,.67,2.65,11.98,3.73,{fill:'0B1120',stroke:'344364'});
  text(s,'Prompt Optimizer Platform',.94,2.86,7.5,.26,12,'A9B6CF',{fontFace:'Segoe UI'});
  badge(s,ui,'工作台',10.95,2.80,1.20);
  const cols=[{x:.92,w:3.17,title:'01  项目上下文'}, {x:4.27,w:3.59,title:'02  原始提示词'}, {x:8.04,w:4.34,title:'03  优化结果'}];
  cols.forEach(c=>{box(s,ui,c.x,3.30,c.w,2.80);text(s,c.title,c.x+.18,3.49,c.w-.36,.30,15,'E9EEF8',{bold:true});});
  badge(s,ui,'添加上下文',1.12,4.01,2.76);
  text(s,'活动资料.docx\n背景说明：面向新用户\n分析报告与覆盖提示',1.14,4.67,2.68,1.04,13,'A9B6CF');
  text(s,'根据活动资料，\n写一段公众号预告。',4.49,4.07,3.08,.75,17,'E9EEF8');
  badge(s,ui,'Plan 确认：可选',4.49,5.03,3.12);
  badge(s,ui,'增强提示词',4.49,5.55,3.12,ui.accent,true);
  text(s,'背景    活动资料与目标受众\n任务    整理预告写作要求\n输出    篇幅、标题与正文\n约束    保留事实与待确认事项',8.28,4.04,3.91,1.22,14,'E9EEF8');
  badge(s,ui,'编辑',8.27,5.55,1.02);badge(s,ui,'复制',9.43,5.55,1.03);badge(s,ui,'再次增强',10.62,5.55,1.50);
  caption(s,t,'界面结构示意，使用人工演示数据。实际界面以当前运行版本为准。');
}
function enhance(s,t) {
  intro(s,t,'示例：把活动预告的要求，整理成可以直接交给模型的任务说明。');
  box(s,t,.67,2.65,4.20,3.60,{fill:t.id==='editorial'?t.panel2:t.panel});
  badge(s,t,'原始提示词',.94,2.93,1.48);
  text(s,'根据活动资料，\n写一段公众号预告。',.96,3.50,3.62,.95,26,t.text,{bold:true,fontFace:t.heading});
  text(s,'用户补充\n面向新用户，正文约 200 字\n另外提供 3 个标题',.97,4.77,3.55,1.02,16,t.muted);
  line(s,t,5.07,4.41,5.58,4.41,true,t.accent);
  const rows=[['背景','依据活动资料，面向新用户'],['任务','整理公众号预告的写作要求'],['输出','约 200 字正文，另给 3 个标题'],['约束','只用已确认事实，缺失信息待确认']];
  rows.forEach((r,i)=>{let y=2.68+i*.88;box(s,t,5.83,y,6.80,.73,{fill:i%2?t.panel2:t.panel});text(s,r[0],6.05,y+.17,.80,.34,17,t.accent,{bold:true});text(s,r[1],7.01,y+.16,5.32,.37,17,t.text);});
  caption(s,t,'人工编写的结构节选。实际输出还包括验收标准、平台约束及必要的待确认事项。');
}
function context(s,t) {
  intro(s,t,'项目代码、办公资料和补充说明，都可以成为本次任务的上下文。');
  const a=[['代码目录','语言、框架与依赖\n目录概览和相关代码块','code'],['文档材料','PDF 文字层\nOffice 文档文本','doc'],['手动说明','任务目标与偏好\n已经确认的规则和限制','check']];
  a.forEach((v,i)=>{let x=.67+i*4.08;box(s,t,x,2.62,3.83,2.23,{square:t.id==='editorial'});icon(s,t,v[2],x+.23,2.90,.53);text(s,v[0],x+.94,2.95,2.52,.36,23,t.text,{bold:true});text(s,v[1],x+.24,3.76,3.37,.80,16.5,t.muted);});
  const flow=['主动选择材料','确认处理范围','检索相关片段'];
  flow.forEach((v,i)=>{let x=.94+i*4.12;circle(s,t,String(i+1),x,5.39,.45);text(s,v,x+.61,5.40,2.85,.36,20,t.text);if(i<2)line(s,t,x+3.25,5.61,x+3.75,5.61,true);});
  caption(s,t,'文件被选中不等于全文进入模型。请查看解析失败、截断和覆盖不足等提示。');
}
function stack(s,t) {
  intro(s,t,'从配置和文件内容识别项目线索，让提示词更贴近现有实现。');
  const groups=[['Java / Spring Boot','Maven、源码与框架线索'],['Node.js / Vue / React','package.json 与前端项目线索'],['TypeScript / Python','语言与项目配置线索'],['Go / Rust','go.mod、Cargo 与源码线索']];
  groups.forEach((v,i)=>{let x=.67+(i%2)*3.73,y=2.70+Math.floor(i/2)*1.48;box(s,t,x,y,3.49,1.22);text(s,v[0],x+.2,y+.20,3.08,.35,19,t.text,{bold:true,fontFace:'Segoe UI Semibold'});text(s,v[1],x+.2,y+.69,3.04,.29,13,t.muted);});
  box(s,t,8.33,2.70,4.28,3.72,{fill:t.panel2});
  text(s,'带进提示词的项目背景',8.59,3.03,3.75,.43,22,t.text,{bold:true});
  ['技术栈与依赖线索','目录概览与相关片段','本次明确的修改范围'].forEach((v,i)=>{circle(s,t,String(i+1),8.66,3.88+i*.73,.36,t.secondary);text(s,v,9.21,3.88+i*.73,3.05,.33,17,t.text);});
  text(s,'识别结果需要核对。\n平台不编译或运行项目。',8.65,5.73,3.68,.44,11.5,t.dim);
}
function plan(s,t) {
  intro(s,t,'当范围或交付要求还没定下来，开启 Plan 确认。默认仍可直接增强。');
  box(s,t,.69,2.68,7.12,3.69,{fill:t.panel});
  badge(s,t,'Plan 确认 · 示意题',.96,2.96,2.25);
  text(s,'1 / 1',6.76,3.00,.68,.30,12,t.dim,{align:'right'});
  text(s,'这份预告，优先突出什么？',.98,3.65,6.55,.47,25,t.text,{bold:true});
  box(s,t,.98,4.37,2.97,.59,{fill:t.panel2});
  text(s,'A   活动亮点',1.18,4.51,2.57,.32,17,t.muted);
  box(s,t,4.21,4.37,3.27,.59,{fill:t.panel2,stroke:t.accent,lineWidth:1.7});
  text(s,'B   报名方式（已选）',4.38,4.51,2.91,.32,17,t.text,{bold:true});
  text(s,'可回看修改；也支持多选和自由输入',1.01,5.33,6.32,.33,14,t.muted);
  text(s,'已核对本题答案',1.03,5.90,2.55,.29,13,t.dim);
  badge(s,t,'生成最终提示词',4.42,5.78,3.05,t.accent,true);
  const steps=[['先看材料','结合可用上下文'],['按需提问','最多 8 题，信息充分时跳过'],['主动确认','推荐项不自动确认，答案可修改'],['最终生成','结合答案准备上下文并生成结果']];
  steps.forEach((v,i)=>{let y=2.72+i*.91;circle(s,t,String(i+1),8.34,y,.42,i<2?t.accent:t.secondary);text(s,v[0],9.02,y-.01,3.60,.37,22,t.text,{bold:true});text(s,v[1],9.03,y+.46,3.56,.35,13.2,t.muted);});
  caption(s,t,'问题与答案为人工示意。实际按任务提问，未知条件仍需保留并在执行前核对。');
}
function developer(s,t) {
  intro(s,t,'演示需求：为超过 48 小时仍未处理的工单增加升级提醒。');
  const blocks=[{x:.68,title:'提供项目依据',n:'01',lines:['Vue + Spring Boot','现有接口与规则说明','已有角色和权限规则']},
    {x:4.78,title:'确认业务决定',n:'02',lines:['接收人：负责人 + 值班管理员','用户确认：不自动关闭工单','重发规则缺失时继续待确认']},
    {x:8.88,title:'形成可检查的任务',n:'03',lines:['保留“严格超过 48 小时”','围绕既有权限组织修改范围','补上测试与执行前确认要求']}];
  blocks.forEach((b,i)=>{box(s,t,b.x,2.81,3.77,3.15,{fill:i===1?t.panel2:t.panel,square:t.id==='editorial'});text(s,b.n,b.x+.25,3.08,1.0,.54,31,i===1?t.accent:t.dim,{fontFace:'Segoe UI Light'});text(s,b.title,b.x+.25,3.85,3.24,.46,22,t.text,{bold:true});b.lines.forEach((v,j)=>text(s,v,b.x+.25,4.52+j*.44,3.29,.38,j===0&&i===1?13.4:14.0,t.muted));});
  if(t.id==='editorial')text(s,'也可识别 React、Python、Go、Rust 等项目线索，具体结果需要核对。',.74,6.15,11.85,.27,12.0,t.muted);
  caption(s,t,'人工讲解样例，非客户成果或真实模型运行记录。平台产出提示词，后续工具负责代码执行。');
}
function scenarios(s,t) {
  intro(s,t,'同一个工作台，组织不同任务的目标、材料和交付要求。');
  const cards=[['办公','会议记录变成行动清单要求','确认决议、负责人、期限','01'],['写作','把活动资料改成预告需求','明确受众、语气、篇幅','02'],['学习','用课程资料规划复习要求','说明范围、时间、练习方式','03'],['研究','根据数据字典整理分析要求','核对来源、指标、方法条件','04']];
  cards.forEach((v,i)=>{let x=.67+(i%2)*6.16,y=2.65+Math.floor(i/2)*1.81;box(s,t,x,y,5.91,1.52,{square:t.id==='editorial'});text(s,v[3],x+.21,y+.29,.83,.64,35,t.accent,{fontFace:'Segoe UI Light'});text(s,v[0],x+1.17,y+.22,4.48,.32,21,t.text,{bold:true});text(s,v[1],x+1.17,y+.67,4.48,.31,16.5,t.text);text(s,v[2],x+1.17,y+1.10,4.48,.27,13.0,t.muted);});
  caption(s,t,'专业内容仍需复核。输入研究计划不代表已有研究结果，输入数据字典不代表已上传数据。');
}
function structure(s,t) {
  intro(s,t,'结构保留必要信息，具体内容随着任务、材料和确认答案变化。');
  const a=[['背景','依据是什么','项目现状、受众和材料'],['任务','要做什么','具体目标和处理范围'],['输出','交付什么','格式、篇幅和成果形式'],['约束','遵守什么','规则、限制和权限红线']];
  a.forEach((v,i)=>{let x=.68+i*3.08;box(s,t,x,2.73,2.81,2.42,{fill:i===3?t.panel2:t.panel});text(s,v[0],x+.23,3.01,2.31,.52,28,t.accent,{bold:true});text(s,v[1],x+.23,3.76,2.31,.39,21,t.text,{bold:true});text(s,v[2],x+.23,4.40,2.31,.44,14,t.muted);});
  const b=[['验收标准','当前版本会补充'],['示例','由开关决定'],['待确认事项','有未决条件时显示']];
  b.forEach((v,i)=>{let x=.80+i*4.13;text(s,v[0],x,5.70,1.65,.33,17,t.text,{bold:true});text(s,v[1],x+1.67,5.70,2.2,.34,13.5,t.muted);});
  caption(s,t,'当前尚未开放任意省略核心结构或验收段落的自然文展示模式。');
}
function control(s,t) {
  intro(s,t,'原始提示词保留，结果可以修改，也可以成为下一次增强的起点。');
  const a=[['编辑','核对事实，调整表述','01'],['复制','带到自己选择的模型或工具','02'],['再次增强','以当前结果作为新的输入','03'],['历史复用','查找记录、查看详情、重新优化','04']];
  a.forEach((v,i)=>{let y=2.68+i*.86;circle(s,t,v[2],.78,y,.44,i%2?t.secondary:t.accent);text(s,v[0],1.47,y+.00,2.2,.40,23,t.text,{bold:true});text(s,v[1],4.15,y+.03,7.92,.34,18,t.muted);});
  caption(s,t,'“复制并打开”后仍由用户粘贴和发送；页面编辑不会覆盖原历史记录。');
}
function privacy(s,t) {
  intro(s,t,'按需提供上下文，在生成前后都保留用户的判断与控制。');
  const rows=[['主动选择','浏览器处理用户选中的文件，发送范围按流程确认'],['过滤与脱敏','过滤受保护路径，处理可识别的敏感内容'],['历史有范围','保存提示词与脱敏摘要，不保存上传文件正文'],['执行有边界','输出权限红线，实际操作仍由用户与下游工具控制']];
  box(s,t,.72,2.76,3.26,3.37,{fill:t.panel2});icon(s,t,'lock',1.76,3.2,1.12,t.accent);text(s,'看清本次\n提供了什么',1.08,4.70,2.55,.87,26,t.text,{bold:true,align:'center',fontFace:t.heading});
  rows.forEach((v,i)=>{let y=2.68+i*.90;text(s,v[0],4.54,y,2.18,.37,21,t.text,{bold:true});text(s,v[1],6.76,y,5.8,.59,15.5,t.muted);});
  caption(s,t,'模型调用会发送本次允许的相关上下文。请只提供有权使用的材料，并检查敏感内容。');
}
function architecture(s,t) {
  intro(s,t,'把较重的处理、安全校验和模型调用，安排在各自合适的位置。');
  const cols=[{x:.72,w:3.42,title:'浏览器工作台',tech:'Vue 3 + Web Worker',body:'交互与本地代码索引\n相关片段检索',icon:'code'},
    {x:4.96,w:3.42,title:'应用服务',tech:'Java 21 + Spring Boot',body:'上下文、Plan 与权限规则\n结果校验与受控重试',icon:'check'},
    {x:9.20,w:3.42,title:'模型供应商',tech:'OpenAI 兼容接口',body:'平台管理路由与密钥\n用户选择已发布模型',icon:'doc'}];
  cols.forEach((c,i)=>{box(s,t,c.x,2.76,c.w,2.39);icon(s,t,c.icon,c.x+.2,3.02,.48);text(s,c.title,c.x+.90,3.06,2.28,.32,22,t.text,{bold:true});text(s,c.tech,c.x+.22,3.70,c.w-.44,.36,16,t.accent,{fontFace:'Segoe UI Semibold'});text(s,c.body,c.x+.22,4.29,c.w-.44,.62,15,t.muted);if(i<2)line(s,t,c.x+c.w+.13,3.97,c.x+c.w+.62,3.97,true);});
  box(s,t,1.00,5.61,5.35,.62,{fill:t.panel2});text(s,'PostgreSQL  保存业务与优化记录',1.20,5.77,4.95,.30,16,t.text);
  box(s,t,6.99,5.61,5.35,.62,{fill:t.panel2});text(s,'Redis  短时状态与共享并发控制',7.20,5.77,4.95,.30,16,t.text);
  caption(s,t,'这些是稳定运行的设计基础。响应速度取决于材料规模、所选模型及部署环境。');
}
function status(s,t) {
  intro(s,t,'当前定位：MVP 本地联调与内部试用，邀请真实任务反馈。');
  const cards=[{x:.69,title:'已实现的核心能力',color:t.good,lines:['直接增强与 Plan 确认','上下文分析与文档文本提取','编辑、复制与优化历史','邮箱登录与已发布模型选择']},
    {x:4.79,title:'当前的使用边界',color:t.warn,lines:['产出提示词，不执行任务','图片仅处理元数据','扫描件尚无 OCR','专业结果需要人工复核']},
    {x:8.89,title:'仍需完善与验证',color:t.accent,lines:['团队管理、额度计费、插件','真实模型业务验收','目标部署环境验收','更多材料与场景覆盖']}];
  cards.forEach(c=>{box(s,t,c.x,2.74,3.74,3.65,{square:t.id==='editorial'});circle(s,t,'',c.x+.26,3.06,.22,c.color);text(s,c.title,c.x+.24,3.62,3.26,.45,21,t.text,{bold:true});c.lines.forEach((v,i)=>text(s,v,c.x+.24,4.33+i*.43,3.26,.34,14.5,t.muted));});
  caption(s,t,'试用范围与正式开放时间，由开发者结合当前版本和验收结果安排。');
}
function closing(s,t) {
  const a=['写下目标','按需添加材料','增强或 Plan 确认','审阅并复制'];
  a.forEach((v,i)=>{let x=.75+i*3.1;circle(s,t,String(i+1),x,2.67,.52);text(s,v,x,3.43,2.88,.43,22,t.text,{bold:true});if(i<3)line(s,t,x+.73,2.93,x+2.75,2.93,true);});
  box(s,t,.75,4.51,11.87,1.26,{fill:t.id==='midnight'?t.panel2:'173B36',stroke:t.id==='midnight'?t.line:'173B36'});
  text(s,'欢迎向开发者申请试用，反馈一次真实体验。',1.04,4.86,11.25,.46,27,t.id==='midnight'?t.text:'F7F3EA',{bold:true,fontFace:t.heading});
  text(s,'QingNiao0x  /  个人开发项目',.79,6.08,8,.38,17,t.muted);
  text(s,'Copyright © 2026 QingNiao0x',.80,6.59,10,.26,11,t.dim,{fontFace:'Segoe UI'});
}

const draw={pain,value,workbench,enhance,context,stack,plan,developer,scenarios,structure,control,privacy,architecture,status,closing};
const sequences={
  midnight:['cover','pain','value','workbench','enhance','context','stack','plan','developer','scenarios','structure','control','privacy','architecture','status','closing'],
  editorial:['cover','pain','enhance','plan','context','structure','developer','scenarios','workbench','control','privacy','architecture','status','closing'],
};
const alternateTitles={
  pain:'一句话，还藏着哪些没有说出的要求？', enhance:'清楚的任务说明，从这些信息开始',
  plan:'关键决定由你确认，AI 才有依据', context:'手边的材料，可以一起带进来',
  structure:'四个问题，把需求说明白', developer:'开发任务，也需要完整的项目背景',
  scenarios:'换一个场景，方法依然适用', workbench:'第一次使用，沿着三栏走一遍',
  control:'结果可以改，好的需求可以复用', privacy:'提供材料之前，边界也要说清',
  architecture:'界面背后，几层设计各有分工', status:'今天能体验什么，哪些还在完善',
  closing:'你的下一个任务，就是最好的开始',
};

function scriptMarkdown(t,slides) {
  const duration=Math.round(slides.reduce((a,b)=>a+b.minutes,0));
  let md=`# Prompt Optimizer Platform：${t.label}逐页讲稿\n\n`+
    `- 受众：最终用户、潜在试用者和客户宣讲听众。\n- 建议时长：约 ${duration} 分钟；加入现场操作可预留 3–5 分钟。\n- 形式：${slides.length} 页，16:9。讲稿也已写入 PPT 演讲者备注。\n- 版本定位：个人开发项目，MVP 本地联调与内部试用。\n- 示例属性：界面重绘、问题与优化结果均为人工演示示意，不是真实客户数据或效果保证。\n\n`;
  slides.forEach(c=>{md+=`## ${String(c.index).padStart(2,'0')}｜${c.title}\n\n**建议时长：${Math.round(c.minutes*60)} 秒**\n\n${c.speech}\n\n**演示动作**：${c.action}\n\n**事实依据**：${c.sources.map(x=>'`'+x+'`').join('、')}\n\n`;});
  return md;
}

(async()=>{
  const manifest=[];
  for(const t of Object.values(themes)) {
    const p=new PptxGenJS();p.layout='LAYOUT_WIDE';p.author='QingNiao0x';p.subject='面向最终用户的产品价值与使用演示';
    p.title=`Prompt Optimizer Platform · ${t.label}`;p.company='个人开发项目';p.lang='zh-CN';
    p.theme={headFontFace:t.heading,bodyFontFace:FONT,lang:'zh-CN'};
    const sequence=sequences[t.id],notes=[];
    for(let i=0;i<sequence.length;i++) {
      const key=sequence[i];
      const s=key==='cover'?cover(p,t,sequence.length):base(p,t,key,i+1,sequence.length,t.id==='editorial'?alternateTitles[key]:undefined);
      if(key!=='cover')draw[key](s,t);
      const n=note(s,key,t,i+1);n.title=t.id==='editorial'?(alternateTitles[key]||'在点击发送之前，先把想法说清楚'):n.title;
      notes.push(n);
    }
    const pptxPath=path.join(OUT,t.file+'.pptx');
    await p.writeFile({fileName:pptxPath});
    fs.writeFileSync(path.join(OUT,t.file+'-逐页讲稿.md'),scriptMarkdown(t,notes),'utf8');
    manifest.push({id:t.id,file:t.file+'.pptx',slideCount:sequence.length,slides:sequence.map((key,i)=>({number:i+1,key,title:notes[i].title}))});
    console.log(`CREATED ${t.file}.pptx (${sequence.length} slides)`);
  }
  fs.writeFileSync(path.join(OUT,'presentation-manifest.json'),JSON.stringify(manifest,null,2)+'\n','utf8');
})().catch(e=>{console.error(e);process.exitCode=1});
