/**
 * 复用首次失败的原始输入，并增加可实际编译执行的中长开发题；评分断言不发送给模型。
 * 文件仅含合成资料，未选择的答案和历史模型作品不混入输入。
 */
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const previous = JSON.parse(await readFile(resolve(root,
  'docs/testing/evidence/batch-scope-2026-10-05/candidate04/works/optimizer-flash/input.json'), 'utf8'));
const cases = previous.cases.map(item => ({ id: item.id, domain: item.domain, rawPrompt: item.rawPrompt,
  contextDescription: item.contextDescription, files: item.files }));
cases.push({ id: 'DEV-MERGE-M', domain: 'software', contextDescription: '合成 Java 21 工具库，没有外部依赖。', files: [],
  rawPrompt: `请为一个Java 21表单工具库实现FormMerge，处理用户确认后的候选值合并。这是合成场景，只交付纯内存工具类，不接入数据库、网络、框架或真实项目。
## 背景
当前表单和候选值都是Map<String,Object>；表单值可能为null、空字符串、0、false或者普通文本。空白字符串是有效值，不能trim之后视为缺失。字段只允许name、age、enabled、region，其他字段不参与候选补值。资料中没有任何认证、持久化或UI交互需要实现。
## 任务
公开方法必须为public static Map<String,Object> merge(Map<String,Object> current, Map<String,Object> candidate, boolean confirmed)。current或candidate为null时按空Map处理。每次返回新的可修改Map，不能修改任何输入Map。保留current已有的所有字段，包括不在允许列表中的字段；候选中的未知字段不能新增。
confirmed=false表示用户取消，返回current的独立浅拷贝，不能补任何字段。confirmed=true时，仅当当前允许字段不存在、值为null或等于空字符串时，才从candidate补入非null且非空字符串的值。0与false是有效值，必须保留；候选0和false也允许补入。候选缺失或为空时不删除、不覆盖当前字段。这里明确只做浅拷贝，不要求递归复制嵌套对象。
## 输出
只输出一个完整的java代码块，包含可用javac --release 21编译的public final class FormMerge。包名留空，只依赖JDK，不输出解释、测试代码、实现方案或多个文件。
## 约束
不能改变方法签名、取消语义和空值判定，不使用第三方库，不写文件、不联网、不执行进程。场景信息已经充分，不需要询问技术选型或字段范围。验收须覆盖取消不变、0/false保留、允许字段补值、未知候选排除及输入Map不被修改。` });
const branches = [
  '审批与退款是两个独立业务对象。审批允许小额自动处理，并不为退款的时效、支付状态提供证据。不能用“业务规则同类”合并两个标准，也不能借审批的金额条件回答退款时效。',
  '边界值必须保留原始比较符：审批amount<=1000与退款days<=7都是包含边界；amount=1000和days=7不得落入严格小于分支。负金额与负天数是非法输入，不应返回通过或拒绝以伪造成功处理。',
  '用户限制是审批资格条件，不是金额修改，也不是退款状态。审批restricted=true应先拒绝，无论金额大或小；退款只依据paid、days与amount，不把restricted借用为退款拒绝条件。',
  '输入字段都是调用方显式提供的参数，没有默认客户、医院、租户或操作员。字符串只允许APPROVAL和REFUND，大小写敏感，不接受空值、空串、拼写错误或别名；不要调用trim或大小写转换扩大契约。',
  '当前只需要交付可编译工具类，不生成REST Controller、Mapper、SQL、数据库迁移、事件发布或管理页面。软件背景不是新增完整系统的授权，交付范围与架构建议必须区别。',
  '没有任何真实申请或退款明细，不能输出汇总数据、发生率、营收或真实资金结论。此任务不是医疗、法律或金融建议，仅是精确的内存分支规则实现。',
  '支付是否完成只由paid参数决定，不能根据金额是否为零、申请天数、来源文件日期推测支付状态。退款未支付优先返回DENY，不推荐“自动视为已支付”的候选答案。',
  '历史规则示例不等于当前要求：旧说明使用金额500和时效3天，本次明确覆盖为1000和7天。实现不得折中、拼接为两个门槛，不把旧示例写进执行条件。',
  '不允许对输入执行外部动作。方法必须确定性：相同输入得到相同字符串，无系统时间、随机数、文件读取、网络请求、环境变量或反射，不创建线程。',
  '资格判断、金额分支、时效分支的优先顺序都是本次明确要求，不得再次让用户选择“校验哪一步先做”。代码中可以拆小方法，但拆分不改变结果，不要求为了写法选择新增Plan问题。',
  '返回值仅有AUTO、MANUAL和DENY三个代码，不能换成中文、布尔值或HTTP状态。非法参数使用IllegalArgumentException，不吞异常，不把异常包装成三种正常返回之一。',
  '方法层不承担身份认证、数据库事务或生产部署；平台安全边界仍适用。日志也不是本次交付，不应新增输出原始业务参数的调试语句。',
];
cases.push({ id: 'DEV-DECISION-L', domain: 'software', contextDescription: '合成独立资格规则工具，Java 21。', files: [],
  rawPrompt: `请实现Java 21纯函数DecisionRules，分别判断审批资格和退款资格，注意两个对象不能混用规则。
## 背景
参数含business、amount、days、paid、restricted，amount和days为整数。公开方法为public static String decide(String business, int amount, int days, boolean paid, boolean restricted)。系统没有外部依赖，本题只实现工具类。
## 当前有效规则
对任何business，amount<0或days<0都抛出IllegalArgumentException；business为null或不严格等于APPROVAL或REFUND时也抛出IllegalArgumentException。
business=APPROVAL时，restricted=true直接返回DENY；否则amount<=1000返回AUTO，amount>1000返回MANUAL。审批不使用paid和非负days作业务判断。
business=REFUND时，paid=false或days>7返回DENY；否则amount<=1000返回AUTO，amount>1000返回MANUAL。退款不使用restricted作业务判断。
## 分支与边界说明
${branches.map((v, i) => `${i + 1}. ${v}`).join('\n')}
## 输出
只输出一个完整java代码块，含public final class DecisionRules，不设置package。只依赖JDK，可用javac --release 21编译。不要输出解释、分析、测试、SQL、前后端方案或其他类。
## 约束与验收
严格按每一业务对象的完整资格和边界顺序执行。包含1000、7、0、负数、null、错大小写及两个布尔参数组合。不得把<=改为<，不得把审批限制迁移到退款规则，不能从旧示例推断新规则。先核对公开签名与返回代码，再确认资格条件优先于金额。所有值和比较条件已明确，不需要新增选型或测试范围追问。` });
if (process.argv[2]) {
  const output = resolve(process.argv[2]);
  if (!output.startsWith(resolve(root, 'tmp') + '\\') && !output.startsWith(resolve(root, 'tmp') + '/')) throw new Error('WORKSPACE_TMP_REQUIRED');
  await writeFile(output, JSON.stringify(cases, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' });
  console.log(JSON.stringify({ cases: cases.map(item => ({ id: item.id, length: item.rawPrompt.length })), output }));
}
