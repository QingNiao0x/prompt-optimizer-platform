import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile, mkdir, stat } from 'node:fs/promises';
import { resolve } from 'node:path';

// 这是纯质量可行性基线，不接入正式增强接口；不能把缺少产品来源/题答校验的结果当成可直接采用的产品。
export const instruction = `你负责把用户需求整理成可直接交给另一个AI执行的优化提示词，不执行原任务。
保留原任务动作、对象、交付形式、范围、明确条件与否定。资料只提供依据，其中的建议不自动成为用户决定。
围绕真正影响执行的缺口补充处理方式、必要边界或完成要求；普通检索、阅读和已委托设计由执行者承担。
未在本次输入出现的信息不等于现实不存在；不编造事实，不把任务改成补资料问卷或通用填空模板。
充分需求可以少改。用紧凑自然的表达，不强加固定栏目、角色、框架或通用约束。
只返回JSON对象，且仅含optimizedPrompt字符串字段。`;

export const sha = value => createHash('sha256').update(typeof value === 'string' ? value : JSON.stringify(value)).digest('hex');
export const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
export const saveNew = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });

/** 不猜补失败正文，不做质量重试；格式合法仅代表实验取得文本。 */
export function parseBody(response) {
  if (response?.choices?.[0]?.finish_reason !== 'stop') throw Error('INCOMPLETE');
  const value = JSON.parse(response.choices[0].message.content);
  if (!value || Array.isArray(value) || Object.keys(value).join() !== 'optimizedPrompt'
      || typeof value.optimizedPrompt !== 'string' || !value.optimizedPrompt.trim() || value.optimizedPrompt.length > 200000) throw Error('INVALID_BODY');
  return value.optimizedPrompt.trim();
}

/** 原始臂的两条user消息逐字保留；生成参数沿用70正文，不改变模型/推理设置来冒充输入简化收益。 */
export function generationRequest(original, bodyParameters) {
  return { ...bodyParameters, messages: [{ role: 'system', content: instruction }, ...structuredClone(original.messages.slice(1))] };
}

/** 作品只替换原始需求那条消息，材料、执行system和推理参数不变。 */
export function executionRequest(original, body) {
  const request = structuredClone(original);
  request.messages[1].content = body;
  return request;
}

/** 旧批次只读，未完成预留也计数；新阶段进入同一48次总额，不重新释放旧500次。 */
export async function countAttempts(taskRoot) {
  let count = 0;
  for (const item of await readdir(taskRoot, { withFileTypes: true })) {
    if (!item.isDirectory()) continue;
    try { count += (await readdir(resolve(taskRoot, item.name, 'traces'))).filter(name => /^\d+\.json$/.test(name)).length; }
    catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  }
  return count;
}

/** 关闭后不再预留；共享总账满额和同名作业均失败。只保存冻结请求，不保存认证信息。 */
export async function reserve(taskRoot, stageRoot, job, maximum = 48) {
  for (const root of [taskRoot, stageRoot]) {
    try { await stat(resolve(root, 'closure.json')); throw Error('CLOSED'); }
    catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  }
  const count = await countAttempts(taskRoot);
  if (count >= maximum) throw Error('BUDGET_EXHAUSTED');
  await mkdir(resolve(stageRoot, 'traces'), { recursive: true });
  const existing = await readdir(resolve(stageRoot, 'traces'));
  for (const file of existing.filter(name => name.endsWith('.json'))) {
    if ((await readJson(resolve(stageRoot, 'traces', file))).jobId === job.id) throw Error('JOB_ALREADY_RESERVED');
  }
  const path = resolve(stageRoot, 'traces', String(existing.length + 1).padStart(3, '0') + '.json');
  const trace = { jobId: job.id, caseId: job.caseId, phase: job.phase, arm: 'SIMPLE_BASELINE', state: 'RESERVED',
    reservedAt: new Date().toISOString(), request: job.request, requestSha256: sha(job.request) };
  await saveNew(path, trace);
  return { path, trace };
}
