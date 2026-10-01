// Opt-in regression against a running API with a real Provider; inputs are fixed synthetic samples.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const environment = globalThis.process?.env ?? {};
const argumentsList = globalThis.process?.argv ?? [];
const directRun = Boolean(argumentsList[1]) && import.meta.url === pathToFileURL(resolve(argumentsList[1])).href;
const baseUrl = environment.PLAN_EVAL_BASE_URL;
const email = environment.PLAN_EVAL_EMAIL;
const password = environment.PLAN_EVAL_PASSWORD;
const releaseMode = argumentsList.includes('--release');
const reviewMode = argumentsList.includes('--review');
if (directRun && (!baseUrl || !email || !password)) {
  console.error('Set PLAN_EVAL_BASE_URL, PLAN_EVAL_EMAIL and PLAN_EVAL_PASSWORD in the process environment.');
  process.exit(2);
}

// 交互验收复用同一批样例和断言；导入本模块不会登录或触发模型调用。
export const cases = [
  {
    id: 'software_mixed_order_approval', category: 'software',
    rawPrompt: '按上传的订单审批方案，在现有 Spring Boot 项目实现订单审批接口。',
    contextDescription: 'Java 21、Spring Boot 3 订单服务', expectedTemplate: 'FEATURE_DEVELOPMENT',
    files: [
      { path: 'pom.xml', language: 'xml', content: '<project><properties><java.version>21</java.version></properties><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>' },
      { path: 'docs/订单审批方案.txt', language: 'text', content: '订单金额超过五万元时必须先由财务复核。审批通过后才可以创建出库单。' }
    ],
    mustNotAsk: [/这项研究|研究地区|研究范围/, /项目使用什么.*框架|采用什么.*技术栈/],
    finalTerms: ['五万元', '财务复核'],
  },
  {
    id: 'research_known_data_dictionary', category: 'research',
    rawPrompt: '分析 2015—2025 年广东省心脑血管疾病死亡率趋势，按性别、年龄组和城乡比较，并计算 YLL 与 YLL 率，采用 Arriaga 分解。',
    contextDescription: '公共卫生研究', expectedTemplate: 'RESEARCH_ANALYSIS',
    files: [{ path: 'docs/研究数据字典.txt', language: 'text', content: '研究范围：广东省\n数据来源：死因登记中心\n数据格式：CSV\n疾病亚类：缺血性心脏病、脑卒中\n人群划分：按性别、5岁年龄组、城乡\n分析工具：R\n分析方法：Arriaga分解' }],
    mustNotAsk: [/研究地区是哪里|具体.*地区|覆盖.*区域/, /数据来源.*什么|数据.*从哪里/, /数据.*格式.*什么/, /疾病.*亚类.*标准|病种.*分类/, /人群.*如何.*分|年龄组.*标准/, /分析.*工具.*什么|使用.*软件/],
    finalTerms: ['广东省', '死因登记中心', 'Arriaga'],
  },
  {
    id: 'research_missing_region', category: 'research',
    rawPrompt: '比较 2015—2025 年某地区心脑血管疾病死亡率长期趋势、季节性趋势和 YLL 变化。',
    contextDescription: '研究资料已明确数据来源为死因登记中心、格式为 CSV。', expectedTemplate: 'RESEARCH_ANALYSIS',
    files: [{ path: 'docs/数据说明.txt', language: 'text', content: '数据来源：死因登记中心\n数据格式：CSV\n时间范围：2015—2025年' }],
    mustAsk: [/地区|区域|省|市/],
    finalTerms: ['2015', '死因登记中心'],
  },
  {
    id: 'education_explicit_audience', category: 'education',
    rawPrompt: '为初中一年级学生设计一节 45 分钟的一元一次方程课程，包含课堂活动、分层练习和单独列出的答案。',
    contextDescription: '', expectedTemplate: 'GENERAL', files: [],
    mustNotAsk: [/目标读者是谁|面向哪些学生|学生是什么年级/],
    finalTerms: ['初中一年级', '一元一次方程'],
  },
  {
    id: 'business_explicit_output', category: 'business',
    rawPrompt: '分析零售门店本季度促销效果，按门店和商品类别比较，输出 Excel 表格及三条可执行建议。',
    contextDescription: '', expectedTemplate: 'GENERAL', files: [],
    mustNotAsk: [/输出.*什么格式|交付.*什么形式/],
    finalTerms: ['本季度', 'Excel', '三条'],
  },
  {
    id: 'writing_explicit_audience', category: 'writing',
    rawPrompt: '面向没有编程基础的成年初学者，写一篇约 800 字的 Python 入门科普，语气轻松，配两个生活化例子。',
    contextDescription: '', expectedTemplate: 'GENERAL', files: [],
    mustNotAsk: [/目标读者是谁|面向什么人|读者具备什么基础/],
    finalTerms: ['成年初学者', 'Python', '生活化例子'],
  },
  {
    id: 'operations_explicit_segment', category: 'operations',
    rawPrompt: '为沉睡 90 天以上的老客户制定春节促活方案，渠道限企业微信，输出分周执行计划和预算表。',
    contextDescription: '', expectedTemplate: 'GENERAL', files: [],
    mustNotAsk: [/目标受众是哪些人|面向哪些客户|使用什么渠道/],
    finalTerms: ['老客户', '企业微信', '预算表'],
  },
  {
    id: 'legal_explicit_jurisdiction', category: 'legal-writing',
    rawPrompt: '基于中国大陆现行法律，为普通租房者撰写一份租赁合同风险提示清单；说明这不是个案法律意见，并按风险等级排序。',
    contextDescription: '', expectedTemplate: 'GENERAL', files: [],
    mustNotAsk: [/适用哪个法域|司法辖区.*哪个/],
    finalTerms: ['中国大陆', '风险等级'],
  },
  {
    id: 'software_goal_research_attachment', category: 'mixed-intent',
    rawPrompt: '开发订单统计接口，读取订单表中的金额和状态，返回按月份汇总的 JSON。',
    contextDescription: '现有 Java 服务', expectedTemplate: 'FEATURE_DEVELOPMENT',
    files: [{ path: 'docs/旧研究方案.txt', language: 'text', content: '研究范围：浙江省。研究对象为 2015—2025 年死亡率，按 Arriaga 方法分析。' }],
    mustNotAsk: [/这项研究|研究地区|研究范围|死亡率|arriaga/i],
    finalTerms: ['订单', '月份', 'JSON'],
  },
  {
    id: 'cross_file_threshold_conflict', category: 'conflict',
    rawPrompt: '根据新审批方案实现订单审批，遇到规则冲突时请先让我确认。',
    contextDescription: '订单服务', expectedTemplate: 'FEATURE_DEVELOPMENT',
    files: [
      { path: 'src/main/resources/审批规则.txt', language: 'text', content: '审批阈值：三万元' },
      { path: 'docs/新审批方案.txt', language: 'text', content: '审批阈值：五万元' }
    ],
    mustAsk: [/审批阈值|不同取值|采用哪一项|哪份资料/],
    finalTerms: ['三万元', '五万元'],
    answers: [{ match: /审批阈值|不同取值/, answer: '本次以新审批方案中的五万元阈值为准。' }],
  },
  {
    id: 'complete_translation_no_plan_questions', category: 'complete-request',
    rawPrompt: '请把“Good morning, everyone.”翻译成简体中文，保留原句的问候语气，只输出译文。',
    contextDescription: '', expectedTemplate: 'GENERAL', files: [],
    expectNoQuestions: true, finalTerms: ['早上好', '大家'],
  },
];

const duplicateDimensions = [
  /(?:研究|分析|覆盖|具体).{0,12}(?:地区|区域|省份|城市)|(?:地区|区域).{0,12}(?:范围|哪里|哪个)/,
  /(?:数据|资料).{0,10}(?:来源|来自)|(?:来源|来自).{0,10}(?:数据|资料)/,
  /(?:数据|文件|材料).{0,10}(?:格式|类型)|(?:格式|类型).{0,10}(?:数据|文件|材料)/,
  /(?:疾病|病种).{0,10}(?:亚类|分类|定义)/,
  /(?:人群|年龄|城乡|性别).{0,10}(?:划分|分组|标准)/,
  /(?:分析|统计|编程).{0,10}(?:工具|软件|语言)/,
  /(?:输出|交付).{0,10}(?:格式|形式)/,
  /(?:验收|成功|评价).{0,10}(?:标准|条件)/,
];

const cookies = new Map();
function absorbCookies(response) {
  for (const entry of response.headers.getSetCookie?.() ?? []) {
    const match = /^([^=;]+)=([^;]*)/.exec(entry);
    if (match) cookies.set(match[1], match[2]);
  }
}
function cookieHeader() {
  return [...cookies].map(([key, value]) => `${key}=${value}`).join('; ');
}
async function call(path, body) {
  const response = await fetch(new URL(path, baseUrl), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      cookie: cookieHeader(),
      'X-XSRF-TOKEN': decodeURIComponent(cookies.get('XSRF-TOKEN') ?? '')
    },
    body: JSON.stringify(body)
  });
  absorbCookies(response);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status} (${payload.error?.code ?? 'unknown'})`);
  return payload.data;
}

export function createAnswers(questions, sample) {
  return questions.map(question => {
    const specific = sample.answers?.find(item => item.match.test(question.question));
    return {
      questionId: question.id,
      question: question.question,
      answer: specific?.answer
        ?? question.options?.find(option => option.recommended)?.answer
        ?? question.options?.[0]?.answer
        ?? question.examples?.[0]
        ?? '请使用材料中明确的唯一事实；若没有唯一事实，请说明未知并指出需要补充的资料。'
    };
  });
}

export function evaluateQuestions(sample, questions) {
  const text = questions.map(question => question.question).join('\n');
  const knownRepetitions = (sample.mustNotAsk ?? []).filter(pattern => pattern.test(text)).length;
  const requiredMissing = (sample.mustAsk ?? []).filter(pattern => !pattern.test(text)).length;
  const offTopic = (sample.offTopic ?? []).filter(pattern => pattern.test(text)).length;
  const semanticDuplicates = duplicateDimensions.reduce((total, pattern) => {
    const matches = questions.filter(question => pattern.test(question.question)).length;
    return total + Math.max(0, matches - 1);
  }, 0);
  return { knownRepetitions, requiredMissing, offTopic, semanticDuplicates };
}

export function redact(value) {
  return String(value ?? '')
    .replace(/(api[ _-]?key|access[ _-]?token|client[ _-]?secret|password)\s*[:=]\s*\S+/gi, '$1=[REDACTED]')
    .replace(/\bsk-[A-Za-z0-9_-]{10,}\b/g, '[REDACTED_TOKEN]')
    .replace(/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g, '[REDACTED_PRIVATE_KEY]');
}

if (directRun) {
const csrf = await fetch(new URL('/api/v1/auth/csrf', baseUrl));
absorbCookies(csrf);
await call('/api/v1/auth/login', { email, password });
const results = [];
for (const sample of cases) {
  try {
    let planningContext = null;
    if (sample.files.length) {
      const prepared = await call('/api/v1/context/planning', {
        rawPrompt: sample.rawPrompt,
        context: { customDescription: sample.contextDescription, files: sample.files }
      });
      planningContext = { contextId: prepared.contextId, version: prepared.version };
    }
    const plan = await call('/api/v1/optimizations/plan', {
      rawPrompt: sample.rawPrompt,
      contextDescription: sample.contextDescription,
      planningContext
    });
    const questions = plan.questions ?? [];
    const questionMetrics = evaluateQuestions(sample, questions);
    const answers = createAnswers(questions, sample);
    const final = await call('/api/v1/optimizations', {
      rawPrompt: sample.rawPrompt,
      context: { customDescription: sample.contextDescription, files: sample.files },
      planConfirmation: { planId: plan.planId, planningContext: plan.planningContext, answers }
    });
    const finalText = final.optimizedPrompt ?? '';
    const missingTerms = sample.finalTerms.filter(term => !finalText.includes(term));
    const conflictAsked = !(sample.category === 'conflict')
      || (sample.mustAsk ?? []).some(pattern => pattern.test(questions.map(item => item.question).join('\n')));
    const noQuestionsCorrect = !sample.expectNoQuestions || questions.length === 0;
    const templateCorrect = plan.templateCode === sample.expectedTemplate;
    const realProvider = plan.provider?.mock === false && final.provider?.mock === false;
    const passed = !questionMetrics.knownRepetitions && !questionMetrics.requiredMissing
      && !questionMetrics.offTopic && !questionMetrics.semanticDuplicates
      && !missingTerms.length && conflictAsked && noQuestionsCorrect && templateCorrect && realProvider
      && questions.length <= 8;
    results.push({
      id: sample.id, category: sample.category,
      provider: plan.provider?.provider ?? 'unknown', model: plan.provider?.model ?? 'unknown',
      realProvider, questionCount: questions.length, ...questionMetrics,
      templateCorrect, missingFacts: missingTerms.length, conflictAsked,
      noQuestionsCorrect, planLatencyMs: plan.latencyMs ?? null,
      finalLatencyMs: final.latencyMs ?? null, passed,
      questions: questions.map(question => question.question),
      finalPrompt: redact(finalText),
      finalAmbiguities: (final.ambiguities ?? []).map(redact),
    });
  } catch (error) {
    results.push({ id: sample.id, category: sample.category, error: error.message, passed: false });
  }
}

console.table(results.map(({ questions, finalPrompt, finalAmbiguities, ...metrics }) => metrics));
const automaticFailures = results.filter(result => !result.passed);
const failures = [...automaticFailures];
if (reviewMode) {
  console.log('\nSynthetic outputs for domain review (credentials redacted):');
  console.log(JSON.stringify(results.map(({ id, category, questions, finalPrompt, finalAmbiguities, error }) => ({
    id, category, questions: (questions ?? []).map(redact), finalPrompt, finalAmbiguities, error
  })), null, 2));
}

if (releaseMode) {
  try {
    const review = JSON.parse(readFileSync('docs/testing/plan-mode-human-review.json', 'utf8'));
    const expectedIds = new Set(cases.map(sample => sample.id));
    const reviewed = new Map((review.cases ?? []).map(entry => [entry.id, entry]));
    const reviewerCount = new Set((review.reviewers ?? []).map(value => String(value).trim()).filter(Boolean)).size;
    const allReviewed = reviewerCount >= 2 && review.reviewedAt
      && [...expectedIds].every(id => {
        const item = reviewed.get(id);
        return item?.questionRelevance === 'PASS'
          && item?.factFidelity === 'PASS'
          && item?.noKnownFactRepetition === 'PASS';
      });
    if (!allReviewed) {
      failures.push({ id: 'human_review', passed: false, error: '需要两名评审完成全部样例的相关性、事实保真和重复提问判定。' });
    }
  } catch {
    failures.push({ id: 'human_review', passed: false, error: '缺少可读取的人工评测记录。' });
  }
}

console.log(`\n${results.length - automaticFailures.length}/${results.length} cases passed automatic checks.`);
if (releaseMode && failures.some(result => result.id === 'human_review')) {
  console.error('Release gate blocked: complete the human review record after inspecting --review output.');
}
process.exitCode = failures.length ? 1 : 0;
}
