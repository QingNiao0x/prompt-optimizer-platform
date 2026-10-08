/**
 * 动态平台条款的真实验收入口。复用已正常登录的本地浏览器与现有证据工具；只提交合成材料。
 * setup 冻结输入和默认模型；Plan 问题先收集，再经逐题审核文件确认，不自动采用推荐项。
 */
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createFinalCandidateAcceptance } from './final-candidate-business-acceptance.mjs';

const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('node scripts/dynamic-constraint-acceptance.mjs setup|direct|plan|finish|check|history <new-or-existing-run-directory> [case-id] [reviewed-answer-json]');
  process.exit(0);
}
const [action, directory, caseId, answerFile] = args;
if (!['setup', 'direct', 'plan', 'finish', 'check', 'history'].includes(action) || !directory) throw new Error('INVALID_ARGUMENTS');
const output = resolve(directory);
const code = { path: 'src/DemoApplication.java', language: 'java', content:
  'package demo; import org.springframework.boot.autoconfigure.SpringBootApplication; @SpringBootApplication public class DemoApplication {}' };
const guide = { path: 'docs/usage.md', language: 'markdown', content:
  '演示平台支持输入需求、选择本地资料、按需计划确认、生成提示词、编辑和复制。平台只生成提示词，不直接执行用户任务。' };
const data = { path: 'docs/data-dictionary.md', language: 'markdown', content:
  '合成月度数据为CSV，month字段格式YYYY-MM，count字段为非负整数。缺失值保持缺失，不能补零。当前任务只设计分析方法，不提供真实结果。' };
const cases = [
  { id: 'CODE', domain: 'software', rawPrompt: '编写 Python 函数，将非空字符串列表按长度升序排序，长度相同时保持原顺序。空列表返回空列表，不修改传入列表。交付完整代码和三个调用示例。', files: [] },
  { id: 'GUIDE', domain: 'writing', rawPrompt: '根据资料编写给新用户的中文使用指南，正文600至800字，使用编号步骤。只说明已经提供的能力，不新增接口，不编写代码。', files: [code, guide] },
  { id: 'MIXED', domain: 'analysis-with-code', rawPrompt: '制定月度数据质量分析方案，交付中文说明和可运行的 R 脚本。输入字段按数据字典，缺失值保持缺失，不得补零，不计算真实研究结果。', files: [code, data] },
  { id: 'REPORT', domain: 'analysis', rawPrompt: '用 R 设计月度数据质量分析，交付中文方法说明和空表，不提供代码，不计算真实结果。缺失值保持缺失，不得补零。', files: [code, data] },
  { id: 'PLAN_YES', domain: 'analysis', rawPrompt: '根据资料制定月度数据质量分析方案，交付中文报告和空表。不计算真实结果，缺失值保持缺失，不得补零。是否额外提供可运行的 R 脚本尚未确定，请在 Plan 阶段询问是否交付代码。', files: [code, data] },
  { id: 'PLAN_NO', domain: 'analysis', rawPrompt: '根据资料制定月度数据质量分析方案，交付中文报告和空表。不计算真实结果，缺失值保持缺失，不得补零。是否额外提供可运行的 R 脚本尚未确定，请在 Plan 阶段询问是否交付代码。', files: [code, data] },
];
const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
try {
  const page = browser.contexts().flatMap(context => context.pages()).find(item => item.url().startsWith('http://127.0.0.1:5175/'));
  if (!page) throw new Error('AUTHENTICATED_LOCAL_PAGE_REQUIRED');
  const session = await createFinalCandidateAcceptance(page, { output, cases, resume: action !== 'setup', callsPerModelLimit: 12 });
  const frozen = action === 'setup' ? session.models : JSON.parse(await readFile(resolve(output, 'catalog.json'), 'utf8')).models;
  const model = frozen.find(item => item.defaultModel);
  if (!model) throw new Error('PUBLISHED_DEFAULT_MODEL_REQUIRED');
  const prefix = id => `${id}-${model.id.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
  if (action === 'setup') console.log(JSON.stringify({ status: 'FROZEN', model: model.id, version: model.displayName, cases: cases.length }));
  else if (action === 'direct' || action === 'plan') {
    const result = await session.collect(caseId, model.id, action);
    console.log(JSON.stringify({ caseId, status: result.status, questions: result.plan?.questions,
      provider: result.result?.provider, request: result.requests.at(-1), failure: result.failure }));
  } else if (action === 'finish') {
    const plan = JSON.parse(await readFile(resolve(output, `${prefix(caseId)}-plan.json`), 'utf8'));
    const answers = JSON.parse(await readFile(resolve(answerFile), 'utf8'));
    const result = await session.finish(plan, answers);
    console.log(JSON.stringify({ caseId, status: result.status, provider: result.result?.provider, request: result.requests.at(-1), failure: result.failure }));
  } else if (action === 'history') {
    const checks = [];
    for (const sample of cases) {
      const arm = sample.id.startsWith('PLAN_') ? 'plan-final' : 'direct';
      const evidence = JSON.parse(await readFile(resolve(output, `${prefix(sample.id)}-${arm}.json`), 'utf8'));
      const list = await session.call(`/api/v1/optimization-history?current=1&size=20&keyword=${encodeURIComponent(sample.rawPrompt.slice(0, 16))}`,
        undefined, `${sample.id}-history-list`);
      let matched;
      for (const record of list.records.filter(item => new Date(item.createdAt) >= new Date(evidence.startedAt))) {
        const detail = await session.call(`/api/v1/optimization-history/${record.id}`, undefined, `${sample.id}-history-detail`);
        if (detail.rawPrompt === sample.rawPrompt && detail.optimizedPrompt === evidence.result.optimizedPrompt) {
          matched = detail;
          break;
        }
      }
      checks.push({ caseId: sample.id, saved: !!matched,
        sectionsEqual: !!matched && JSON.stringify(matched.sections) === JSON.stringify(evidence.result.sections),
        appliedEqual: !!matched && JSON.stringify(matched.appliedConstraints) === JSON.stringify(evidence.result.appliedConstraints),
        nonMock: matched?.mock === false });
    }
    // 只保存合成验收记录的比对结论，不导出历史列表、其他用户输入或登录信息。
    await session.save('dynamic-constraint-history-checks', { checkedAt: new Date().toISOString(), checks });
    console.log(JSON.stringify(checks));
    if (checks.some(item => !item.saved || !item.sectionsEqual || !item.appliedEqual || !item.nonMock)) process.exitCode = 1;
  } else {
    const rules = [
      '明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。',
      '不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。',
      '输出应直接回应用户目标，遵守已明确的交付范围和格式；仅在任务需要且未限制额外说明时，说明关键依据、适用范围和限制条件。',
      '禁止读取或输出受保护路径：.env、**/*.pem、**/*.key、生产环境配置。',
      '以下操作必须先获得人工确认：删除或覆盖文件、数据库结构迁移、升级核心依赖、生产环境部署。',
    ];
    const checks = [];
    for (const sample of cases) {
      const arm = sample.id.startsWith('PLAN_') ? 'plan-final' : 'direct';
      const evidence = JSON.parse(await readFile(resolve(output, `${prefix(sample.id)}-${arm}.json`), 'utf8'));
      const result = evidence.result;
      const content = result?.sections.find(section => section.type === 'CONSTRAINTS')?.content ?? '';
      const marker = '平台强制约束（不得删除或弱化）：';
      const actual = content.split(marker).at(-1).trim().split('\n').filter(line => line.startsWith('- ')).map(line => line.slice(2));
      const expected = ['CODE', 'MIXED', 'PLAN_YES'].includes(sample.id) ? rules : rules.slice(0, 1);
      checks.push({ caseId: sample.id, status: evidence.status, model: result?.provider.model, mock: result?.provider.mock,
        platformRuleCount: actual.length, exactRules: JSON.stringify(actual) === JSON.stringify(expected),
        singleBlock: content.split(marker).length === 2, copiedSection: !!result?.optimizedPrompt.includes(content),
        appliedVisible: !!result?.appliedConstraints.every(rule => content.includes(rule)),
        requestId: evidence.requests.at(-1)?.requestId, ambiguities: result?.ambiguities, latencyMs: result?.latencyMs });
    }
    await session.save('dynamic-constraint-checks', { checkedAt: new Date().toISOString(), checks,
      scope: 'Fixed display, copied sections and applied constraints; not unrestricted semantic or downstream-work acceptance.' });
    console.log(JSON.stringify(checks));
    if (checks.some(item => item.status !== 'COLLECTED' || item.mock !== false || !item.exactRules || !item.singleBlock || !item.copiedSection || !item.appliedVisible)) process.exitCode = 1;
  }
} finally {
  // 断开本脚本 CDP 连接，浏览器由原验收守护进程管理，不导出任何登录凭据。
  await browser.close();
}
