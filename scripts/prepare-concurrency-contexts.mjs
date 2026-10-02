/**
 * 为显式授权的付费压测准备合成资料；复用生产项目索引与检索算法。
 * 仓库适配器只用内存，避免读取开发者项目或浏览器持久化数据。
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const requireWeb = createRequire(path.join(root, 'apps/web/package.json'));
const { build } = requireWeb('esbuild');
const output = path.join(root, 'services/api/target/real-model-fixtures');
await mkdir(output, { recursive: true });
await build({ entryPoints: [path.join(root, 'apps/web/src/features/project-index/projectIndexer.ts')],
  bundle: true, platform: 'node', format: 'esm', alias: { '@': path.join(root, 'apps/web/src') },
  outfile: path.join(output, 'project-indexer.mjs') });
const { indexProject, retrieveProjectContextWithReport } = await import(pathToFileURL(path.join(output, 'project-indexer.mjs')));

class MemoryRepository {
  files = []; chunks = [];
  async beginProject(summary) { this.summary = summary; }
  async completeProject(summary) { this.summary = summary; }
  async pauseProject(summary) { this.summary = summary; }
  async failProject(_id, message) { throw new Error(message); }
  async findFiles(id, paths) { return this.files.filter(f => f.projectId === id && paths.includes(f.path)); }
  async writeBatch(files, chunks, replaced = []) {
    this.chunks = this.chunks.filter(c => !replaced.includes(c.path));
    for (const [target, values] of [[this.files, files], [this.chunks, chunks]]) {
      for (const item of values) { const i = target.findIndex(f => f.id === item.id); if (i < 0) target.push(item); else target[i] = item; }
    }
  }
  async removeUnseenFiles(id, scan) {
    const removed = this.files.filter(f => f.projectId === id && f.lastSeenScanId !== scan).map(f => f.path);
    this.files = this.files.filter(f => !removed.includes(f.path));
    this.chunks = this.chunks.filter(c => !removed.includes(c.path));
    return removed.length;
  }
  async findCandidateChunks(id, terms, limit) {
    return this.chunks.filter(c => c.projectId === id && (c.priority === 0 || !terms.length
      || terms.some(t => c.searchTerms.includes(`${id}:${t}`)))).slice(0, limit);
  }
  async findChunksByPaths(id, paths, limit) { return this.chunks.filter(c => c.projectId === id && paths.includes(c.path)).slice(0, limit); }
  async deleteProject(id) { this.files = this.files.filter(f => f.projectId !== id); this.chunks = this.chunks.filter(c => c.projectId !== id); }
}

const shared = `请把这段需求整理成可以直接交给另一位执行者的结构化提示词，包含背景信息、具体任务、输出形式和约束条件。当前平台只负责生成提示词，本次请求不要求平台执行实际业务，也不要求连接其他系统。请阅读我主动上传的资料，把能够从资料确认的事实写入提示词；资料没有给出的数值、人员姓名、地址或系统能力标为待确认，不要凭经验补全。资料中有一个以大写英文字母和下划线书写的关键规则代号，请在最终提示词中保留其完整拼写，并解释该规则所对应的业务约束，便于执行者核对资料来源。
输出面向有一定工作经验但没有参与前期讨论的协作者，语言使用中文，层次清楚，先说明要解决的问题和完成标准，再给出分阶段执行步骤与需要返回的结果。不要用空泛的“优化体验”“确保稳定”代替可观察的行为；每项检查尽量说明输入、操作和预期结果。先列出已有信息，再单独列出未决问题，避免把示例、建议或者候选方案写成已经确认的事实。若同一资料存在矛盾，应指出出处并保留为待确认事项，不能自行挑选一个版本。
我希望结果可以在一次阅读后直接使用，保留必要的业务术语，不需要长篇背景介绍，也不需要复制全部上传内容。权限边界是只处理这次需求明确涉及的内容，不修改无关文件，不访问真实用户的私人资料，不发送外部消息，不执行上线发布或不可逆操作。最终请给出清晰的验收清单，覆盖正常流程、输入缺失、失败提示、重复操作以及恢复后的处理，并说明哪些结论来自资料、哪些步骤还需要后续确认。`;
const documentPrompt = `我负责一个社区公开课的活动筹备工作，需要依据上传的活动手册准备一份下一次活动的执行方案。参与者是成年人，组织者是志愿者，活动包含签到、分组讨论、茶歇和反馈收集。请遵循手册中已经确定的时间、人数与签到规则，特别关注迟到处理、现场容量、物料交接和志愿者之间的信息同步。预算和具体日期尚未确定，请将其列为待确认。\n${shared}`;
const projectPrompt = `我正在维护一个社区场地预约项目，上传了 Vue 和 Java 项目的代码。用户偶尔连续点击预约按钮，页面会出现重复预约提示不清楚的问题。请依据现有 ReservationService、预约页面、数据访问代码和测试，整理一份修复需求提示词。沿用当前登录身份与工作区隔离，明确重复请求的返回行为，说明前端如何显示处理结果。不要假设数据库已经具有代码中不存在的约束；只在必要时提出最小改动，并保留历史兼容性。\n${shared}`;

const chapters = [
  ['活动范围', '公开课每场最多四十名成年参与者，由两名签到志愿者、一名主持人和一名场地协调员协作。开场前二十分钟开放签到；规则代号为 WORKSHOP_CHECKIN_20M。代号与业务规则均需要写入交付材料，签到不得早于该时间开放。'],
  ['签到流程', '志愿者确认参与者报名序号和场次，不记录身份证件。重复签到只提示已签到，不增加人数。名单缺失时交场地协调员核对，不能让志愿者临时编造身份或自动加入名单。'],
  ['场地容量', '现场容量达到四十人后停止新增入场，请主持人说明候场安排。离场人数由协调员核对后更新，不因为一个页面刷新就增加空位。不得使用未批准的额外场地。'],
  ['分组讨论', '讨论时间为四十分钟，每组围绕同一阅读材料提出一个问题。主持人说明发言顺序与记录方式，反馈以匿名纸卡收集，不把参与者姓名和联系方式写进公开纪要。'],
  ['物料准备', '物料清单包括纸卡、签字笔、计时器、饮水和场次标识。数量依据最终报名人数核对，未确定采购单价和预算总额的部分保留空白，不推定供应商或付款方式。'],
  ['异常处置', '遇到名单服务不可用时，由场地协调员保管纸质备份并进行人工核对。恢复后先对账再更新记录，避免重复登记。联系人的姓名与电话需要正式安排后补充。'],
  ['志愿者交接', '每次交接记录已签到人数、候场人数、未解决的问题和待确认事项。接班者核对时间、场次和记录版本；不通过公共群转发参与者资料。'],
  ['活动反馈', '活动结束后汇总匿名反馈的主题和行动建议，具体评价不与个人身份关联。缺失信息说明未收集，不根据观察猜测参与者年龄、职业或健康情况。'],
  ['验收要求', '执行方案必须包含活动前、活动中、活动后步骤，负责人使用角色名称。每一步写清触发条件、操作和完成标准，异常分支与恢复分支应可以由志愿者逐条检查。'],
  ['未决事项', '场次日期、场地地址、采购预算、联系人姓名、报名服务供应商尚未确认。执行方案列出这些问题的影响和确认责任，但不能填入示例值作为事实。'],
];
const documentText = '# 社区公开课活动手册（合成测试资料）\n' + chapters.map(([heading, body], i) =>
  `\n## ${i + 1}. ${heading}\n${body}\n现场核对：${body}\n记录要求：本章节的信息由活动协调员复核，任何临时变化均记入场次交接单。书面方案要注明依据来自本章节；示例不作为已批准事项。\n`).join('');

const files = [
  ['package.json', JSON.stringify({name:'synthetic-reservation',dependencies:{vue:'3.5.0',axios:'1.8.4'}})],
  ['pom.xml', '<project><properties><java.version>21</java.version></properties><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId><version>3.4.4</version></dependency></dependencies></project>'],
  ['README.md', '# 社区预约项目\nVue 3、Java 21、Spring Boot、PostgreSQL。预约范围必须包含租户、工作区和当前用户；不能读取其他工作区。保留现有 API 接口，输入必须校验。\n' + chapters.map(([h,b]) => `预约运营参考 ${h}：${b.replaceAll('WORKSHOP_CHECKIN_20M','现场手册规则')}\n`).join('')],
  ['services/ReservationService.java', `package example.reservation;\nimport java.time.LocalDate;\nimport java.util.UUID;\n/** 场地预约应用服务；上限由已确认业务规则约束。 */\npublic class ReservationService {\n  public static final String RULE_CODE = "RESERVATION_WINDOW_8D";\n  public static final int MAX_ADVANCE_DAYS = 8;\n  private final ReservationRepository repository;\n  public ReservationService(ReservationRepository repository) { this.repository = repository; }\n  /** 只允许提前八天以内预约；同一用户同一场次再次提交时返回已有记录。 */\n  public Reservation create(UUID tenant, UUID workspace, UUID user, String slot, LocalDate day) {\n    if (day.isAfter(LocalDate.now().plusDays(MAX_ADVANCE_DAYS))) throw new IllegalArgumentException(RULE_CODE);\n    var existing = repository.findActive(tenant, workspace, user, slot);\n    if (existing != null) return existing;\n    return repository.insert(tenant, workspace, user, slot, day);\n  }\n}\n`],
  ['services/ReservationRepository.java', `package example.reservation;\nimport java.time.LocalDate;\nimport java.util.UUID;\n/** 仅声明持久化接口；数据库约束由迁移确认，不能推定本接口保证竞争下唯一。 */\npublic interface ReservationRepository {\n  Reservation findActive(UUID tenant, UUID workspace, UUID user, String slot);\n  Reservation insert(UUID tenant, UUID workspace, UUID user, String slot, LocalDate day);\n}\n`],
  ['services/Reservation.java', 'package example.reservation;\npublic record Reservation(String id, String slot, String status) {}\n'],
  ['web/src/ReservationPage.vue', `<script setup lang="ts">\nimport {ref} from 'vue';\nimport {createReservation} from './reservationApi';\nconst loading = ref(false); const message = ref('');\nasync function submit(slot:string) {\n  loading.value = true;\n  try { await createReservation(slot); message.value = '预约完成'; }\n  catch { message.value = '预约失败，请稍后重试'; }\n  finally { loading.value = false; }\n}\n</script>\n<template><button :disabled="loading" @click="submit('morning')">预约</button><p>{{message}}</p></template>\n`],
  ['web/src/reservationApi.ts', `import axios from 'axios';\n/** 身份来自会话；客户端不传入租户与用户身份。 */\nexport async function createReservation(slot:string) { return axios.post('/api/reservations', {slot}); }\n`],
  ['docs/reservation-requirements.md', '# 预约修复验收依据\n规则代号 RESERVATION_WINDOW_8D 表示最多提前八天预约；优化提示词必须保留代号及八天约束。\n' + Array.from({length:12},(_,i)=>`场景 ${i+1}：${['同账号重复提交返回已有预约，不再增加数量。','不同工作区的预约不能相互读取或合并。','页面等待响应时显示处理中，完成后恢复按钮。','超时后允许查询状态，不能把未知结果展示为成功。','逻辑删除的预约不应出现在活动列表；恢复规则需要确认。','数据库竞争控制目前只有查询后插入，迁移中没有唯一约束，需要在修复方案中核对。'][i%6]} 验证应记录输入、步骤与预期结果，先确认需求，再实施最小变更。\n`).join('')],
  ['tests/ReservationServiceTest.java', 'package example.reservation;\n/** 现有测试覆盖八天边界和顺序重复；并发重复场景尚未覆盖。 */\nclass ReservationServiceTest { void rejectsDayNine() {} void acceptsDayEight() {} void returnsExistingReservation() {} }\n'],
  ['db/V1__reservation.sql', 'CREATE TABLE reservation (id uuid primary key, tenant_id uuid not null, workspace_id uuid not null, user_id uuid not null, slot varchar(64), day date, deleted_at timestamptz);\n'],
];
// 无关文件和受保护文件用于验证真实索引过滤；其中不含任何有效凭据。
files.push(['.env', 'SYNTHETIC_PRIVATE_FILE=must-not-leave-index']);
for (let i=0;i<20;i++) files.push([`web/src/catalog/Category${i}.ts`, `export const category${i} = {name:'与预约任务无关的分类展示',visible:true};\n`]);
const repository = new MemoryRepository();
const started = performance.now();
const index = await indexProject({projectId:'synthetic-reservation',rootName:'synthetic-reservation',repository,
  entries:(async function*(){for(const [name,content] of files) yield {kind:'file',path:name,file:new File([content],name,{lastModified:1})};})()});
const retrieval = await retrieveProjectContextWithReport(repository,{projectId:index.id,query:projectPrompt,
  activeFilePath:'services/ReservationService.java',pinnedPaths:['docs/reservation-requirements.md']});
assert.equal(index.status,'READY');
assert.ok(retrieval.files.some(f=>f.content.includes('RESERVATION_WINDOW_8D')));
assert.ok(!retrieval.files.some(f=>f.path === '.env' || f.content.includes('must-not-leave-index')));
for (const prompt of [documentPrompt,projectPrompt]) assert.ok(prompt.length >= 500 && prompt.length <= 1500);
const fixture = { generatedAt:new Date().toISOString(), syntheticOnly:true,
  document:{rawPrompt:documentPrompt,content:documentText,marker:'WORKSHOP_CHECKIN_20M'},
  project:{rawPrompt:projectPrompt,marker:'RESERVATION_WINDOW_8D',files:retrieval.files},
  indexEvidence:{algorithm:'apps/web/src/features/project-index/projectIndexer.ts',storage:'in-memory adapter',
    sourceFiles:files.length,indexedFiles:index.indexedFiles,ignoredFiles:index.ignoredFiles,
    selectedFiles:retrieval.files.length,selectedCharacters:retrieval.totalCharacters,selections:retrieval.selections,
    elapsedMs:Math.round(performance.now()-started),protectedFileExcluded:true} };
await writeFile(path.join(output,'contexts.json'),JSON.stringify(fixture,null,2),'utf8');
console.log(JSON.stringify({output:path.relative(root,output),documentPromptCharacters:documentPrompt.length,
  documentCharacters:documentText.length,projectPromptCharacters:projectPrompt.length,indexEvidence:fixture.indexEvidence},null,2));
