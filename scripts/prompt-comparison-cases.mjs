/**
 * 提示词业务对照的固定合成案例；答案与验收要求只供评测使用，不放入 Qoder 资料目录。
 * 不读取真实患者、账号、密钥或生产配置；匹配不到预设答案时保留“暂不确定”。
 */
import { cases as previousCases } from './plan-quality-eval.mjs';
import { frontendMigration, notificationChoice, notificationCorpus } from './plan-business-cases.mjs';

const sample = id => {
  const original = previousCases.find(item => item.id === id);
  if (!original) throw new Error(`Missing fixed comparison case: ${id}`);
  return { ...original, answerRules: [], requiredRules: [], prohibitedClaims: [] };
};

const baselineFiles = [
  {
    path: 'src/surveys/baselineApi.ts', language: 'typescript',
    content: `export const BASELINE_FILL_FIELDS = ['name', 'idNumber', 'residenceAddress'];
export async function queryBaselineForFill(name: string, idNumber: string) {
  return fetch('/api/baseline/match?' + new URLSearchParams({ name, idNumber }));
}
export async function loadBaselineDetail(id: string) {
  return fetch('/api/baseline/' + encodeURIComponent(id));
}`,
  },
  {
    path: 'docs/基线匹配现状.md', language: 'markdown',
    content: '# 合成工程现状\n两个调查模块为 know-survey 与 behav-survey，均调用 baselineApi.ts。前端为 Vue 3 + TypeScript，后端为 Java 21 + Spring Boot 3。\n登录用户所属地区与患者基线现住址是两个独立字段；服务端负责地区范围过滤。当前是否包含下级地区尚未决定。\n列表返回地址摘要，自动填充前必须查询详情。填充字段使用 BASELINE_FILL_FIELDS，不包括调查日期、调查人和题目。\n同一患者可能有多条记录，选择规则尚未决定。',
  },
  {
    path: 'src/occupational/occupationNotes.md', language: 'markdown',
    content: '# 无调用依赖的职业信息模块\n该模块列表默认查询当前日期；空查询参数从 queryParam 读取。该规则仅适用于职业信息模块，不是基线匹配规则。',
  },
];

const baselineAnswerRules = [
  { match: '地区|下级|范围|属地', answer: '按服务端当前登录用户所属地区及其下级地区匹配患者基线现住址；不能匹配其他同级地区。' },
  { match: '姓名|身份|识别|匹配条件', answer: '患者姓名与身份证号必须同时匹配；缺少任一项时不进行匹配，不回退到仅姓名匹配。' },
  { match: '最新|多条|排序|时间|优先', answer: '多条有效记录按调查日期降序、记录 ID 降序确定唯一记录；不以更新时间排序。' },
  { match: '覆盖|已有|空字段|冲突', answer: '用户确认后只填充 null 或空字符串字段，保留已有值，数字 0 和布尔 false 不是空值。' },
  { match: '填充|字段|提示|交互|自动', answer: '匹配成功先弹窗确认；用户同意才填充 BASELINE_FILL_FIELDS，取消时保持表单不变。' },
  { match: '异常|失败|未匹配|无匹配', answer: '无匹配不弹窗，接口异常显示可读提醒但允许继续手工录入，不能放宽地区条件重试。' },
];

const baseline = {
  id: 'region_autofill_missing_decisions', category: 'software-region',
  rawPrompt: '完善防治知识知晓情况和防护行为调查的基线匹配：用户录入效果评价时，按当前用户所属地区匹配患者基线。当前地区没有、只有其他地区有时不得匹配；当前地区有时提示用户是否自动填充基本信息。',
  contextDescription: '合成的地区调查工程，材料无真实个人信息。',
  expectedTemplate: 'FEATURE_DEVELOPMENT', files: baselineFiles,
  answerRules: baselineAnswerRules,
  requiredRules: ['两个调查模块均覆盖', '地区范围来自登录用户', '其他同级地区不得命中', '用户确认后才填充', '保留已有字段值'],
  prohibitedClaims: ['把职业信息的默认当前日期用作基线查询过滤', '把用户所属地区等同于患者现住址', '无匹配时改为跨地区查询'],
  mustAsk: [/下级|地区.*范围|多条|最新|覆盖|空字段/], finalTerms: ['地区', '填充'],
};

export const comparisonCases = [
  baseline,
  {
    ...baseline, id: 'region_autofill_complete', category: 'complete-software',
    rawPrompt: baseline.rawPrompt + '\n已确定：包含下级地区但排除其他同级地区；姓名和身份证号同时匹配，缺项时不查询；多条有效记录按调查日期降序、ID 降序选一条；经用户确认只填 null 或空字符串，保留 0 和 false；先查询详情，再填 BASELINE_FILL_FIELDS，不填调查日期、调查人和题目。取消保持原值，无匹配不弹窗，接口异常提醒但不阻断手工录入。请生成用于修改现有工程并补测试的实施提示词，不扩展业务范围。',
    files: baselineFiles.map(file => file.path === 'docs/基线匹配现状.md'
      ? { ...file, content: file.content.replace('当前是否包含下级地区尚未决定。', '本次要求匹配当前地区与下级地区。').replace('同一患者可能有多条记录，选择规则尚未决定。', '同一患者可能有多条记录，本次规则由原始提示词明确。') } : file),
    expectNoQuestions: true, mustAsk: [],
  },
  {
    ...sample('cross_file_threshold_conflict'),
    answerRules: [{ match: '审批|阈值|金额|三万|五万|冲突|取值', answer: '本次采用新方案：订单金额严格大于五万元才需要财务复核，等于五万元不需要；旧三万元规则不再作为本次执行标准。' }],
    requiredRules: ['Plan 应暴露三万元和五万元冲突', '确认后只执行严格大于五万元', '不能将严格大于改为大于等于'],
    prohibitedClaims: ['把旧三万元规则继续作为本次执行规则'],
  },
  {
    id: 'analytics_negated_test_constraint', category: 'software-analysis',
    rawPrompt: '审查现有统计日志模块并设计补充指标，只输出设计报告，本轮不修改代码。保留上海时区和左闭右开日期边界，不得把测试样例当作真实业务事实。报告需包含现状清单、缺口、数据来源和优先级。',
    contextDescription: 'Java 21 + Spring Boot 3；Vue 3 + TypeScript 管理页面。',
    expectedTemplate: 'FEATURE_DEVELOPMENT',
    files: [
      { path: 'src/analytics/AnalyticsViews.java', language: 'java', content: 'package demo.analytics; public record AnalyticsViews(long pageViews, long activeAccounts, long optimizationCount) {}' },
      { path: 'docs/统计口径.md', language: 'markdown', content: '# 当前统计口径\n已实现 PV、活跃账户、增强次数。日期范围为上海时区左闭右开。尚未实现付费订单或营收统计。' },
      { path: 'src/test/java/analytics/ExampleFixture.java', language: 'java', content: 'class ExampleFixture { String example = "输出格式：Excel；数据分析工具：Python；营收统计已实现"; }' },
    ],
    answerRules: [{ match: '范围|优先|指标|受众|使用者', answer: '管理员使用，优先补充增强成功率、失败原因和功能使用频率；没有付费业务，不新增营收需求。' }],
    requiredRules: ['输出设计报告而不是实施修改', '不因否定的测试字样选择 TESTING', '已实现指标与未实现能力分开'],
    prohibitedClaims: ['营收统计已实现', '项目使用 Python 分析'], finalTerms: ['上海', '优先级'],
  },
  {
    ...frontendMigration, answerRules: [
      { match: '范围|页面', answer: '迁移全部现有页面，但分阶段实施；本轮仅提供方案，不修改文件。' },
      { match: '策略|节奏|方式|步骤', answer: '采用分阶段增量迁移，保持原有路由与鉴权语义。' },
      { match: '组件|路由|状态|依赖|选型', answer: '先核查实际依赖和页面调用；未提供的组件库、路由库和状态库不作已采用断言。' },
    ],
    requiredRules: ['区分 React 现状和 Vue 3 目标', '仅方案不改代码', '范围与迁移策略不是互斥选项'],
    prohibitedClaims: ['未经材料支持宣称已使用 React Router', '把现有项目说成从零开发'],
  },
  {
    ...sample('legal_explicit_jurisdiction'),
    answerRules: [
      { match: '受众|对象|谁|读者', answer: '普通租房者，面向没有法律专业背景的成年人。' },
      { match: '地区|法域|法律', answer: '中国大陆；具体条文由后续执行 Agent 核验，不虚构法条。' },
      { match: '风险|排序|级别|形式|格式', answer: '按高、中、低风险排列，每条说明风险、核对材料和应对建议；不是对数字或字符串编写排序程序。' },
    ],
    requiredRules: ['已明确普通租房者与中国大陆', '风险等级属于清单组织规则'],
    prohibitedClaims: ['询问待排序的数据类型是数字还是字符串'],
  },
  {
    ...sample('research_known_data_dictionary'),
    answerRules: [
      { match: '地区|区域|范围', answer: '广东省，时间范围 2015—2025 年。' },
      { match: '来源|格式|工具|软件|亚类|分组', answer: '沿用数据字典：死因登记中心 CSV，缺血性心脏病与脑卒中，性别、5 岁年龄组、城乡，分析工具为 R。' },
      { match: '代码|输出|交付|报告', answer: '需要可复现的 R 分析代码、图表及方法说明；不要求现在执行分析或编造结果。' },
      { match: '生命表|人口|标准|季节|月度|缺失', answer: '暂不确定', },
    ],
    requiredRules: ['保留数据字典的已知事实', 'Arriaga 与 YLL 保留', '缺少生命表或人口等材料时不得伪造'],
    prohibitedClaims: ['改变研究地区', '无依据替换 R 为 Python'],
  },
  {
    ...sample('research_missing_region'),
    answerRules: [
      { match: '地区|区域|省|市|范围', answer: '本次分析福建省，时间仍为 2015—2025 年。' },
      { match: '来源|格式', answer: '死因登记中心，CSV 格式。' },
      { match: '工具|软件|代码|实现', answer: '使用 R，提供代码与分析方案，不编造实际结果。' },
    ],
    requiredRules: ['Plan 询问尚未明确地区', '确认后以福建省为研究地区'], prohibitedClaims: ['未询问便把某地区写成具体省份'],
  },
  {
    ...sample('education_explicit_audience'),
    answerRules: [
      { match: '学生|年级|受众|基础', answer: '初中一年级，已学有理数运算，尚未系统学习一元一次方程。' },
      { match: '目标|难度|分层|重点', answer: '理解等式性质并解简单一元一次方程，提供基础题与提高题。' },
      { match: '活动|形式|人数|设备', answer: '普通班级，小组活动，不依赖多媒体或实验设备。' },
      { match: '时长|时间', answer: '45 分钟。' },
    ], requiredRules: ['45 分钟', '初中一年级', '分层练习与答案单列'], prohibitedClaims: [],
  },
  {
    ...sample('complete_translation_no_plan_questions'), finalTerms: ['Good morning, everyone.', '简体中文'],
    requiredRules: ['生成翻译任务的提示词，不能直接执行成只有译文', '原句、简体中文、问候语气和只输出译文的要求保留', '不应额外提问'], prohibitedClaims: [],
  },
  {
    ...sample('software_goal_research_attachment'),
    files: [
      { path: 'src/order/Order.java', language: 'java', content: 'package demo.order; import java.math.BigDecimal; import java.time.LocalDateTime; public record Order(long id, BigDecimal amount, String status, LocalDateTime createdAt) {}' },
      ...sample('software_goal_research_attachment').files,
    ],
    answerRules: [
      { match: '月份|时间|日期|时区', answer: '按 createdAt 所在上海时区的自然月汇总，日期参数采用左闭右开区间。' },
      { match: '状态|口径|金额', answer: '仅统计 PAID 状态，返回订单数量和金额总和。' },
      { match: '输出|格式|接口', answer: '输出 JSON，每月含 month、orderCount、totalAmount。' },
    ], requiredRules: ['主要任务为订单统计接口', '研究附件不能成为订单业务事实'], prohibitedClaims: ['把死亡率或 Arriaga 纳入订单实现'],
  },
  {
    ...notificationChoice, files: [...notificationChoice.files, ...notificationCorpus],
    answerRules: [
      { match: '渠道|邮件|站内信|方式', answer: '本次只接入站内信，不接入邮件；维护已读状态并按用户隔离。' },
      { match: '触发|时机|对象|接收', answer: '订单审批通过后通知订单提交者。' },
      { match: '失败|异常|事务', answer: '通知失败记录可追踪状态，不回滚已通过的审批；具体重试次数尚未决定。' },
    ], requiredRules: ['Plan 询问渠道', '确认后任务只接入站内信', '保留已读状态与用户隔离'], prohibitedClaims: ['将邮件重试三次的规则自动套给站内信'],
  },
];

/** 回答只来自独立答案卡；未知维度保留未知，不自动接受模型推荐。 */
export function fixedAnswers(questions, currentCase) {
  return questions.map(question => {
    const matches = currentCase.answerRules.filter(rule => new RegExp(rule.match, 'i').test(question.question));
    const answer = [...new Set(matches.map(rule => rule.answer))].join('；') || '暂不确定';
    if (answer.length > 500) throw new Error(`Fixed answer exceeds API limit for ${currentCase.id}`);
    return { questionId: question.id, question: question.question, answer };
  });
}
