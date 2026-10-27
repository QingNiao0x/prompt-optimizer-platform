import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const json = async (path) => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const clone = (value) => structuredClone(value);

/** 构造合法业务DTO，来源明确标为脚本；不把它们称为实际浏览器请求或模型结果。 */
export function createValidationBundle(capturedInput) {
  if (capturedInput.modelId !== 'deepseek:deepseek-flash' || !capturedInput.context?.files?.length) {
    throw new Error('需要已捕获的C01合成项目请求，不接受简化重造的项目材料');
  }
  const generationInput = (rawPrompt, withProject = false) => ({
    ...clone(capturedInput), rawPrompt,
    context: withProject ? clone(capturedInput.context) : { customDescription: '', files: [] },
    conversationHistory: [], planConfirmation: null,
  });
  const planInput = (rawPrompt) => ({ rawPrompt, modelId: capturedInput.modelId,
    contextDescription: '', conversationHistory: [], planningContext: null });
  const samples = [
    {
      id: 'V01', family: 'read-understand-wait',
      rawPrompt: '请先阅读并理解@synthetic-survey-workspace/src/views/records 的现有实现，然后等待我的下一步需求；当前不要修改代码，也不要额外交付说明文档。',
      withProject: true, phase: 'GENERATION',
      acceptance: [
        '保留阅读、理解和等待，以及当前不修改代码和不额外交付文档的明确限制。',
        '45实际完成BODY_GENERATION；充分内容允许持平，不能把持平计为质量收益。',
        '使用项目材料帮助表达，但最终正文不倾倒整份源码，不新增实现任务或准备报告。',
      ],
    },
    {
      id: 'V02', family: 'sufficient-format',
      rawPrompt: '只输出三行，第一行1，第二行2，第三行3；不要添加任何其他内容。',
      phase: 'GENERATION',
      acceptance: [
        '保留只输出三行1、2、3且无其他内容的下游交付要求。',
        '增强阶段交付的是可执行提示词，不能直接完成下游任务输出三个数字。',
        '45实际执行正文生成，允许少改或保持原文；不添加前言、解释、额外格式或交付物。',
      ],
    },
    {
      id: 'V03', family: 'ordinary-public-query', rawPrompt: '请帮我查询全球使用AI最多的职业/行业',
      phase: 'GENERATION',
      acceptance: [
        '继续指导下游查询职业和行业，提供对理解结果有用的组织和执行表达。',
        '没有附件不等于现实没有资料；公开检索和普通整理由AI承担，不变成先补资料的问卷。',
        '统计口径和来源只在支撑结果时说明，不机械取代职业、行业及实际应用内容。',
        '不凭空确定全球统一排名、虚构数据或给用户新增未经确认的研究限制。',
      ],
    },
    {
      id: 'P01', family: 'plan-no-important-unknown', rawPrompt: capturedInput.rawPrompt,
      phase: 'PLAN',
      acceptance: [
        '调查时间不能超过今天已经清楚地包含今天，不再把这一边界变成待确认问题。',
        'Plan应零题；定位代码、选择实现方式和普通检索属于AI受托工作。',
        '本Plan请求没有文件正文；不能将增强阶段未见材料判定为用户现实没有材料。',
      ],
    },
    {
      id: 'P02', family: 'plan-user-owned-boundary-conflict',
      rawPrompt: '请在重点记录模块@synthetic-survey-workspace/src/views/records 的新增和导入功能增加调查时间限制。业务要求一处写“只能填写当日之前的日期”，另一处写“可以填写今天，但不超过今日”；这两处边界尚未统一，是否允许填写今天由我确认。',
      phase: 'PLAN',
      acceptance: [
        '识别今天是否允许这一真正影响结果、且用户保留决定权的冲突。',
        '只询问这一关键日期边界；不将普通实现、材料读取或检索变成额外前置问题。',
        '不能把推荐选项写成用户已确认；保留真实Plan会话、题干、题号和期限供后续回答。',
      ],
    },
  ];
  const cases = samples.map((sample) => ({
    id: sample.id, family: sample.family, rawPrompt: sample.rawPrompt,
    origin: '合法API DTO脚本构造；V01仅复用C01实际UI捕获的项目材料，当前需求本身未经UI捕获。P01/P02按Plan协议无文件正文。',
    contextDescription: '', files: sample.withProject ? clone(capturedInput.context.files) : [],
    executorMaterials: [], acceptance: sample.acceptance,
  }));
  const jobs = samples.map((sample) => ({
    id: `${sample.id}_V45_${sample.phase === 'PLAN' ? 'PLAN' : 'GEN'}`,
    phase: sample.phase, caseId: sample.id, version: '45',
    inputOrigin: 'SCRIPT_CONSTRUCTED_LEGAL_API_DTO',
    approvedEnhancementInput: sample.phase === 'PLAN' ? planInput(sample.rawPrompt)
      : generationInput(sample.rawPrompt, sample.withProject),
  }));
  return {
    syntheticOnly: true,
    provenance: '仅C01/N01原始项目题有独立真实UI捕获，本bundle新增题目不冒称UI评测。所有验收卡仅供评估，不发送模型。',
    callBoundary: { taskMaximum: 200, previousActual: 158, maximumNewAcrossAllStages: 42,
      phaseLimitsAcrossAllStages: { GENERATION: 24, PLAN: 6, EXECUTION: 12 },
      qualityRetriesAllowed: false, executions: 0, paidReviews: 0 },
    nextStep: '初始作业仅准备，不在works阶段运行中启动第二个9013。P02真实Plan返回后使用bind-validation-answer.mjs，另建validation-confirmed阶段冻结回答后生成，不能改写本阶段作业。',
    cases, jobs,
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const output = process.argv[2] ? resolve(process.argv[2]) : resolve(evidence, 'validation-bundle.json');
  const capturedInput = await json(resolve(evidence, 'browser-capture/input.json'));
  const bundle = createValidationBundle(capturedInput);
  await writeFile(output, `${JSON.stringify(bundle, null, 2)}\n`, { flag: 'wx' });
  console.log(JSON.stringify({ output, cases: bundle.cases.length, jobs: bundle.jobs.length, modelCalls: 0,
    inputOrigin: 'SCRIPT_CONSTRUCTED_LEGAL_API_DTO', freezeCommand: 'node freeze-live.mjs validation <validation-bundle.json>' }));
}
