/**
 * 多行业业务对照：从白名单路径截取当前项目资料，另附明确标记的合成研究/行情数据。
 * 资料在 prepare 阶段冻结；答案卡仅用于评测，不发送给直接增强或 Qoder 资料目录。
 */
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/** 保留实际相对路径与行号，明确是代码/文档节选，不宣称读取完整项目。 */
async function excerpt(root, path, first, last, language) {
  const source = await readFile(resolve(root, path), 'utf8');
  const content = source.split(/\r?\n/).slice(first - 1, last).join('\n');
  // 出处范围放在评测元数据，不作为正文标签注入业务事实抽取器。
  return { path, language, content, provenance: { firstLine: first, lastLine: last, excerpt: true } };
}

/** 仅读取这里显式列出的非敏感资料，不遍历工作区、认证信息或环境配置。 */
export async function buildIndustryCases(root) {
  const readme = await excerpt(root, 'README.md', 1, 8, 'markdown');
  const planDoc = await excerpt(root, 'docs/12-内置Plan-Mode交互与接口.md', 1, 22, 'markdown');
  const planCode = await excerpt(root, 'apps/web/src/components/prompt/PlanQuestionDialog.vue', 83, 187, 'vue');
  const planningRequest = await excerpt(root, 'apps/web/src/features/optimization/optimizationRequest.ts', 86, 104, 'typescript');
  const common = { expectedTemplate: 'GENERAL', prohibitedClaims: [], requiredRules: [], answerRules: [] };
  return [
    {
      ...common, id: 'project_plan_navigation_audit', category: 'project-code', expectedTemplate: 'FEATURE_DEVELOPMENT',
      rawPrompt: '请基于当前 Prompt Optimizer 项目的 Plan 问答弹窗，检查返回上一题后自定义答案能否保留、生成期间能否重复提交。需要的是代码审阅与最小改进方案，本轮不修改文件；已经实现的行为不要重复开发。输出事实依据、真实缺口、必要时的最小修改位置和正常/异常/边界验收用例，不要扩展为重写整个工作台。',
      contextDescription: 'Vue 3 + TypeScript + Element Plus；提供当前仓库的真实代码与文档节选，不是完整项目。',
      files: [planDoc, planCode, planningRequest],
      answerRules: [
        { match: '保留|返回|切换|草稿|答案', answer: '同一计划内返回上一题保留自定义答案；更换计划时是否恢复以现有逻辑为准，本轮不扩展需求。' },
        { match: '提交|生成|锁定|并发', answer: '最终生成期间不得重复提交或修改本次答案；只审阅已有防护，不直接改代码。' },
        { match: '范围|输出|格式|交付|目标|验证|测试', answer: 'Markdown 代码审阅报告，区分已实现、证据不足和真实缺陷，附最小建议与验收用例；本轮不修改文件。' },
      ],
      requiredRules: ['只审阅不修改', '返回上一题的索引切换不应被写成清空答案', 'isGenerating 防护视为已有证据', '节选未覆盖的行为不能编造'],
      prohibitedClaims: ['现有组件完全没有生成中防护', '必须重新开发已有的上一题功能'],
    },
    {
      ...common, id: 'project_user_guide', category: 'project-documentation',
      rawPrompt: '根据已提供的项目资料，为首次使用 Prompt Optimizer 的普通用户写一份中文操作指南提示词。指南需说明添加上下文、直接增强和 Plan 确认的差异、推荐选项与自定义回答、复制/编辑结果以及完成 Plan 后仍可能出现新冲突提醒。最终 Agent 应输出约 800 字 Markdown 指南，不展示内部字段名或接口参数，不编造截图、收费方案或未实现能力。',
      contextDescription: '使用当前项目 README 与 Plan 文档节选；这是文档写作任务，不是开发指南。', files: [readme, planDoc],
      answerRules: [
        { match: '受众|读者|用户|对象', answer: '首次使用平台、没有开发背景的普通用户。' },
        { match: '风格|形式|结构|格式|输出|步骤|篇幅|场景', answer: '约 800 字中文 Markdown，按操作顺序说明，附一个简单示例和常见问题；不要暴露 API 字段。' },
        { match: '限制|边界|功能|范围', answer: '只解释所给资料明确的功能；提醒 Plan 完成不代表新冲突会被自动清空。' },
      ],
      requiredRules: ['写指南的提示词而非直接交付指南', '保留新冲突提醒', '没有开发任务不要求改代码', '普通用户语言'],
    },
    {
      ...common, id: 'project_beta_press_release', category: 'press-release',
      rawPrompt: '请帮我准备一则 Prompt Optimizer 内测招募新闻稿的写作提示词，面向开发者、科研工作者和学生，在项目公众号发布，正文 600—800 字。突出把模糊需求整理成可执行提示词、可选 Plan 问答和上下文能力。项目仍处于 MVP 本地联调；不得写成已经正式商用，不虚构用户量、准确率、合作机构或节省 Token 比例。发布时间和报名链接尚未确定，正文用明确占位符。',
      contextDescription: '以当前仓库材料为事实来源；内测招募为本次假设写作任务，不代表已对外发布。', files: [readme, planDoc],
      answerRules: [
        { match: '语气|风格|结构|标题|定位', answer: '克制、易懂的产品新闻稿，提供 3 个标题候选与正文，不使用保证效果的宣传词。' },
        { match: '时间|日期|报名|链接|渠道', answer: '公众号发布；发布日期和报名地址用【待定发布日期】和【待填报名链接】，不自行补值。' },
        { match: '受众|读者|范围|目标|亮点', answer: '开发者、科研工作者和学生；招募内测反馈，突出需求表达和可选 Plan，不声称生产可用。' },
      ],
      requiredRules: ['MVP 本地联调与内测假设分开', '禁止虚构效果数据', '保留 600—800 字和公众号', '时间链接占位符'],
      prohibitedClaims: ['正式上线商用', '准确率达到百分比', '已有用户量或真实合作机构'],
    },
    {
      ...common, id: 'project_research_protocol', category: 'academic-paper',
      rawPrompt: '我想撰写一篇研究 Prompt Optimizer 的 Plan 问答是否改善提示词质量的论文。现在只设计研究方案与论文方法部分的写作提示词，不声称完成实验。比较直接增强、Plan 增强与 Qoder 原生增强，在代码、文档、新闻、科研和金融分析五类任务上评测需求保真、事实依据、可执行性、无效提问与延迟。请明确公平对照、盲评和统计方案，不编造样本结果、P 值或参考文献。',
      contextDescription: '项目处于 MVP；提供项目材料及本次研究设计草案，草案不是已完成研究证据。',
      files: [readme, planDoc, { path: 'research/拟定研究设计.md', language: 'markdown', content: '# 拟定研究设计（合成评测材料，尚未执行）\n每行业拟 6 个任务，合计 30 个。每种模式每任务独立重复 3 次。计划由两名评审员盲评。Plan 可获得回答卡中的附加信息，因此另设三组均获得相同确认信息的公平对照。对照工具版本和实际模型记录，无法相同时明确混杂。尚未收集任何实测结果。' }],
      answerRules: [
        { match: '样本|任务数|数量|重复|规模', answer: '拟每行业 6 个任务，共 30 个任务，每组独立重复 3 次；这是设计值，尚未执行。' },
        { match: '公平|对照|信息|答案|盲评|评分|评审', answer: '两名独立评审盲评；主体验组允许 Plan 提问，另设三组输入相同确认信息的对照，单独记录额外信息与工具模型的混杂。' },
        { match: '统计|分析|显著|指标|效应', answer: '优先报告配对差值、置信区间与评审一致性；具体检验按数据分布确定，不预设显著性结论。' },
        { match: '格式|交付|输出|篇幅|期刊', answer: '中文 Markdown 研究方案与论文方法提纲；尚未确定投稿期刊，不编造文献。' },
      ],
      requiredRules: ['拟定研究与实测结果区分', 'Plan 附加信息造成对照差异须处理', '不伪造结果或文献', '两名评审盲评'],
      prohibitedClaims: ['本研究已证明 Plan 优于对照', '编造统计显著性或准确率'],
    },
    {
      ...common, id: 'stock_synthetic_risk_analysis', category: 'stock-analysis',
      rawPrompt: '我想让 AI 分析附件中两只合成股票的走势与风险。请生成分析提示词，基于文件比较区间收益率、波动和最大回撤，说明样本不足及数据口径限制，用中文给出表格、方法和条件化解释。这是教学用历史合成数据，不对应真实证券，不读取实时行情，不提供买卖指令，不预测未来价格。数据是否复权尚未说明，请先明确这一限制。',
      contextDescription: '金融教学场景；代码 SYN_A、SYN_B 是合成标识，日期与数值不代表市场事实。',
      files: [
        { path: 'materials/行情字段说明.txt', language: 'text', content: '合成股票教学数据，不对应真实公司。字段 date 为模拟交易日，close 为合成收盘价格（单位：模拟元），volume 为合成成交量。6 个观测点不足以估计长期风险。没有复权口径、交易成本和分红数据；不得按真实收益率宣传。' },
        { path: 'materials/synthetic-prices.csv', language: 'csv', content: 'date,symbol,close,volume\n2026-09-21,SYN_A,100,1000\n2026-09-22,SYN_A,105,1100\n2026-09-23,SYN_A,102,900\n2026-09-24,SYN_A,108,1200\n2026-09-25,SYN_A,104,950\n2026-09-28,SYN_A,106,1050\n2026-09-21,SYN_B,100,800\n2026-09-22,SYN_B,98,850\n2026-09-23,SYN_B,101,820\n2026-09-24,SYN_B,96,880\n2026-09-25,SYN_B,99,840\n2026-09-28,SYN_B,97,810' },
      ],
      answerRules: [
        { match: '复权|口径|收益|分红|成本', answer: '无法确认复权；仅按所给 close 演示简单价格变化，不把它当真实总回报，不自行补交易成本或分红。' },
        { match: '工具|代码|软件|语言|实现', answer: '提供 Python 计算示例与公式，不安装依赖，不访问外网或执行交易。' },
        { match: '波动|回撤|风险|方法|频率|年化', answer: '用相邻观测点简单收益率的样本标准差与收盘序列峰谷最大回撤，说明口径，不做年化推断。' },
        { match: '范围|时间|区间|日期', answer: '使用附件全部 6 个模拟交易日，仅比较 SYN_A 与 SYN_B，不延伸至真实股票。' },
        { match: '受众|用途|格式|输出|交付', answer: '面向学生的中文方法说明与对照表，列出限制，不给买卖建议或未来价格预测。' },
      ],
      requiredRules: ['只有合成历史数据', '复权未知不能装作确定', '收益率波动最大回撤保留', '不读取实时行情不提供买卖指令'],
      prohibitedClaims: ['宣称数据来自真实行情', '推荐买入 SYN_A 或 SYN_B', '预言未来价格'],
    },
  ];
}
