"""冻结单次Skill对照题目、语义答案卡、调用预算和源码。只生成本地文件。"""
import hashlib
import json
import random
from pathlib import Path

ROOT = Path('docs/testing/evidence/text-polish-skills-2026-11-16')


def save(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sample(identifier, language, text, meanings, style='NATURAL', depth='LIGHT', requirements='', group='pilot'):
    return dict(id=identifier, language=language, text=text, meanings=meanings, style=style,
                depth=depth, requirements=requirements, group=group)


if (ROOT / 'protocol.json').exists():
    raise SystemExit('已有冻结协议，禁止覆盖')
old = json.loads(Path('docs/testing/evidence/text-polish-direct-2026-11-11/protocol.json').read_text(encoding='utf-8'))
digital, service = old['samples'][:2]
samples = [
    sample('p-zh-digital', 'ZH', digital['text'], [
        '数字化推动组织高质量发展', '需求导向与用户价值', '持续优化流程', '感知分析决策执行闭环',
        '强化数据要素作用及打通孤岛', '多维全链深层协同', '配置效率与精细治理', '长效机制及明确目标抓手责任',
        '上下内外协同并持续迭代', '技术和组织能力同步提升', '创新动力', '规范治理及安全底线', '开放合作拓展场景',
        '实践沉淀可复制可推广经验', '场景落地项目推进清单管理', '发展竞争服务三种优势',
        '系统长期基础工程而非短期完成', '问题目标结果统一', '持续投入释放数字动能支撑高质量发展'],
        style='CONVERSATIONAL', depth='RESTRUCTURE', requirements='面向非专业读者，减少套话，保留标题、三段结构、全部核心观点及逻辑顺序。'),
    sample('p-zh-service', 'ZH', service['text'], ['订单完成后7日内可申请', '已使用数字内容不无理由退款',
        '客服收到申请应在3工作日内确认材料完整性', '仅完整后开始5工作日处理期', '不限制法律权利', '不承诺每次退款',
        '保障权益目的', '持续优化流程承诺', '改善服务体验目标'], style='FORMAL'),
    sample('p-en-conditions', 'EN', 'Service update\n\nIn order to protect your account and make the support process easier to use, we plan to introduce an optional recovery check in June. The check may be offered only after you confirm your contact address. Staff should respond within two business days, but a response does not guarantee that access will be restored. Accounts under review remain excluded. We will continue to improve the process to reduce repeated requests and improve the support experience.',
        ['protect account purpose', 'easier support purpose', 'plan optional recovery check in June', 'may offered only after address confirmation',
         'staff should respond in two business days', 'response does not guarantee restoration', 'review accounts excluded',
         'will continue improving', 'reduce repeated requests purpose', 'improve support experience purpose'], style='FORMAL'),
    sample('p-en-natural', 'EN', 'I left the cup on the windowsill. By the time I came back, the tea was cold and the street had gone quiet. I drank it anyway.',
        ['cup left on windowsill', 'came back', 'tea cold', 'street quiet', 'drank anyway'], requirements='Keep the understated personal voice.'),
    sample('p-mixed-technical', 'MIXED', '发布说明\n\n本次测试仅覆盖内部工作区，尚未部署到生产环境。我们计划下周检查导出流程，目的是减少重复操作，并让错误信息更容易理解。\n\nThe export check is optional. It may run only after the owner confirms the workspace. A completed check does not mean that every export will succeed. We will keep the existing manual review for failed items.',
        ['仅内部工作区', '未生产部署', '计划下周检查导出', '减少重复操作目的', '错误信息易懂目的',
         'optional check', 'may only after owner workspace confirmation', 'completed check does not guarantee every export', 'keep manual review failed items'],
        style='CONVERSATIONAL', depth='RESTRUCTURE', requirements='保留双语段落，解释清楚，不补充技术实现。'),
    sample('p-mixed-natural', 'MIXED', '我把车停在桥边，等风小一点再走。河对岸有人关上了窗。\n\nI stayed there until the lights came on. Then I put my gloves back on and rode home.',
        ['桥边停车', '等风小再走', '对岸有人关窗', 'stayed until lights on', 'put gloves back on', 'rode home'], requirements='保留叙事口吻和语言切换。'),
]
# 留出样本在候选资源已冻结后编写，开发模型也负责内部评阅，须披露这一局限。
holdouts = [
 ('zh-mail','ZH','项目材料确认\n\n为进一步提高沟通效率，现就材料整理工作做如下说明。我们已收到设计稿，但预算表仍待财务确认。请在周四中午前只核对目录中的链接是否可用，不需要补写正文。如链接失效，请在同一张表里标记；预算未确认不影响本轮链接检查。我们计划周五汇总问题，是否安排下一轮评审要等负责人确认。', ['收到设计稿','预算待财务确认','周四中午前','仅检查目录链接','无需补正文','坏链接同表标记','预算不影响本轮','计划周五汇总','负责人确认后决定下一轮'], 'FORMAL','LIGHT','写成清楚简洁的同事邮件。'),
 ('zh-story','ZH','父亲第一次学着用手机买车票，连着退回了三次。第四次，他把手机递给我，说你看我是不是又按错了。我指了指右下角的小字，没替他按。车票买成后，他把那页留在屏幕上，过一会儿又点亮看看。回家路上，他忽然问我，下回是不是就不用麻烦你了。', ['父亲首次手机购票','三次退回第四次求看','指小字未代按','买成后反复看屏幕','回家路上问下次不麻烦'], 'NATURAL','LIGHT','保留克制的叙事，避免添加情绪总结。'),
 ('zh-library','ZH','借阅通知\n\n为了更好地营造全民阅读的浓厚氛围，本馆拟于下月试行延长周六开放时间。试行期间，成人阅览室开放至晚上八点，少儿区仍按原时间关闭。读者可在服务台反馈意见，也可以使用线上表单；两种方式均不要求留下手机号。试行是否继续，将结合到馆人数和工作人员排班情况决定，目前尚未形成最终安排。', ['拟下月试行','全民阅读目的','只周六延时','成人至20点','少儿原时关闭','服务台或线上反馈','均无需手机号','继续与否依据人数排班','未最终安排'], 'CONVERSATIONAL','RESTRUCTURE','减少宣传腔，便于居民理解。'),
 ('zh-research','ZH','这次走访涉及两条街道的十二家小店，不能代表整个城区。八位店主提到雨天客流减少，但我们没有收集同期销售额，因此还无法判断营业收入的变化。有人建议统一延长营业时间，也有人担心电费和人手不足。下一步可以先观察不同天气下的到店人数，再讨论是否试行延时；即使人数上升，也不能直接认定是营业时间调整带来的效果。', ['两街十二店范围非全区','八位店主雨天客流减少','无同期销售额不能判收入','延时建议和电费人手担忧','可先观察不同天气人数','再讨论试行','上升不证明延时因果'], 'FORMAL','LIGHT','保留研究限定和分歧。'),
 ('zh-clean','ZH','昨晚收衣服时，我在袖口摸到一粒稻谷。可能是上周去田里时留下的。我把它放进抽屉，想着哪天还给那片地。', ['昨晚收衣袖口稻谷','可能上周田里留下','放抽屉','想着哪天归还'], 'NATURAL','LIGHT','保留原有口吻。'),
 ('zh-long','ZH','社区修理桌\n\n我们打算在旧阅览室试办一个月的周末修理桌，让居民能就近处理一些小故障，也让愿意分享手艺的人有机会认识邻居。这里提供的是共同检查和简单修理，不接收需要专业资质的电器维修。能否修好，要看物件情况和当班志愿者的经验。\n\n报名表只记录姓名、到场时段和物件类别，联系电话选填。居民也可以直接到场，但预约者在自己预约的时段优先。超过时段到场的预约者按现场顺序等待，不能要求别人让位。一次只登记一件物品，零件需要自行购买；志愿者可以说明需要什么，但不会替居民垫付。尚未确定来源或规格的零件，先停下检查。\n\n工具由社区提供，参加者应在使用前听取说明。儿童可以围观，使用工具时须由监护人在旁陪同。对于带有电池、明显漏液或破损的物件，是否接收要由当班负责人判断。这些安排是为了减少风险，不能据此保证物品安全或修理一定成功。\n\n试办期间每周记录到场人数和未完成的主要原因，意见可以当面提出，也可匿名放入纸盒。结束后再与志愿者讨论是否继续。人数多不等于一定继续，还要考虑场地、工具损耗和志愿者是否愿意承担后续安排。我们希望修理桌能减少物品过早丢弃，也希望它成为邻里交流的机会；这两个目标都需要在试办后检视。', ['拟旧阅览室试办一月周末','就近小修及手艺交流结邻目的','共同检查简单修理','不接资质电器','成功视物件志愿者经验','报名姓名时段类别','电话可选','可直接来预约时段优先','迟到按现场顺序不插队','一次一件','自购零件','可说明但不垫资','规格来源不确定暂停','社区工具先听说明','儿童围观使用需监护','电池漏液破损负责人判断','减险非安全成功保证','每周记人数未完成原因','当面或匿名意见','结束讨论继续','人数不唯一依据场地损耗意愿','减少早弃和邻里交流双目标事后检视'], 'CONVERSATIONAL','RESTRUCTURE','保留所有条件、例外和两个目的，重组表达方便居民阅读。'),
 ('en-mail','EN','Subject: Review scope\n\nI am writing to ask whether you could review the links in the draft by Thursday noon. We have received the illustrations, but the cost estimate is still awaiting approval. Please check only whether the links open; there is no need to edit the copy. If a link is broken, mark it in the shared sheet. We plan to collect the results on Friday, although the date of the next review has not been agreed.', ['links review Thursday noon request','illustrations received','estimate awaiting approval','only links no copy edits','broken shared sheet','plan Friday collection','next review date not agreed'], 'FORMAL','LIGHT','Make the email direct and courteous.'),
 ('en-story','EN','Dad tried to book the ticket three times before he handed me the phone. I pointed to the small line at the bottom and let him tap it himself. When the booking went through, he kept the page open. On the walk home he asked whether, next time, he could do it without me.', ['three tries','handed phone','pointed small bottom line','dad tapped himself','booking succeeded page open','walk home asks next time without help'], 'NATURAL','LIGHT','Keep the restrained personal voice.'),
 ('en-notice','EN','Weekend access\n\nIn an effort to enhance the experience of all library users, we are considering a one-month trial of later Saturday opening. During the trial, the adult reading room would stay open until 8 pm, while the children\'s room would close at its usual time. Readers could comment at the desk or through the online form. Neither option requires a phone number. Whether the trial continues would depend on attendance and staff availability; no final schedule has been approved.', ['considering month trial Saturdays','better user experience purpose','adult until8','children usual close','desk or online comments','neither phone required','continuation attendance staffing','no final approved schedule'], 'CONVERSATIONAL','RESTRUCTURE','Make this easy for residents to read, preserving tentative status.'),
 ('en-research','EN','We spoke to twelve shop owners on two streets. The interviews do not represent the whole district. Eight owners said foot traffic fell on rainy days, but we collected no sales figures for those periods. We therefore cannot tell whether revenue changed. Some favored longer opening hours; others were concerned about electricity costs and staffing. Counting visitors in different weather could be a useful next step before discussing a trial. A later increase in visits would not, by itself, establish that longer hours caused it.', ['12 owners2streets nonrepresentative','8 rain traffic','no sales data cannot infer revenue','longer hours support','power staffing concerns','possible weather counts before trial discussion','increase not causal proof'], 'FORMAL','LIGHT','Improve flow without removing research qualifications.'),
 ('en-clean','EN','On the bus, a little girl counted the red cars until we reached the bridge. Then she fell asleep against her mother. I lost count somewhere near the station.', ['bus girl counted red cars','until bridge','sleep against mother','narrator lost count near station'], 'NATURAL','LIGHT','Keep the personal voice.'),
 ('en-long','EN','The repair table\n\nWe plan to run a weekend repair table in the old reading room for one month. We hope it will help residents deal with small faults nearby and give people who share practical skills a chance to meet their neighbors. Volunteers will inspect items together with their owners and attempt simple repairs. The table will not accept electrical work that requires a professional qualification. Success depends on the item and the experience of the volunteers on duty.\n\nThe sign-up form asks for a name, a time slot and the type of item. A phone number is optional. Walk-ins are welcome, but people with bookings have priority during their booked slot. Anyone arriving after that slot joins the walk-in queue. Each person may register one item. Owners must buy any parts they need; volunteers can explain what is required but will not pay on an owner\'s behalf. If a part\'s specification or source is uncertain, the inspection should pause.\n\nThe community center will supply the tools. Participants should listen to the instructions before using them. Children may watch, and a guardian must stay beside a child who uses a tool. The volunteer in charge will decide whether to accept items with batteries, visible leaks or damage. These arrangements aim to reduce risk, but do not guarantee safety or a successful repair.\n\nDuring the trial we will record attendance and the main reasons for unfinished work each week. Residents may comment in person or leave an anonymous note. After the month, volunteers will discuss whether to continue. Attendance alone will not decide the outcome: space, tool wear and volunteers\' willingness also matter. We will review both aims, reducing early disposal and creating opportunities for neighbors to talk.', ['plan weekend old room one month','nearby faults and neighbors purposes','joint inspection simple repairs','exclude qualified electrical','success item experience','name slot item phone optional','walkins booking priority slot','late walkin queue','oneitem','owners buy parts volunteers no advance','uncertain part shouldpause','center tools instructions first','children watch guardian must beside tooluse','incharge acceptance batteries leaks damage','reduce risk no guarantee','weekly attendance unfinished reasons','inperson anonymous','aftermonth discuss','not attendancealone space wear willingness','review disposal and conversation aims'], 'CONVERSATIONAL','RESTRUCTURE','Make the notice easier to scan. Keep all conditions, purposes and obligations.'),
 ('mx-mail','MIXED','请先检查目录链接，预算表仍待财务确认。预算未确认不影响本轮检查。\n\nPlease mark broken links in the shared sheet by Thursday noon. We plan to review the sheet on Friday, but the next meeting date is still undecided.', ['仅链接','预算待确认不影响检查','mark broken links shared sheet Thursday noon','plan Friday review','nextmeeting undecided'], 'FORMAL','LIGHT','保留双语，减少重复铺垫。'),
 ('mx-story','MIXED','父亲买成车票后，把那页留在屏幕上。走到桥边时，他又点亮手机看了一眼。\n\nI did not ask what he was checking. We walked the rest of the way without talking.', ['父亲购票成功屏幕保留','桥边再看','narrator didnot ask','restwalk no talking'], 'NATURAL','LIGHT','保留语言切换和克制的叙事。'),
 ('mx-notice','MIXED','为了方便读者使用，本馆考虑延长周六开放时间，目前尚未批准最终安排。成人阅览室可能开放至晚上八点，少儿区仍按原时间关闭。\n\nReaders may leave feedback at the desk or through the form. Neither option requires a phone number. A decision on continuing the trial would depend on attendance and staff availability.', ['方便读者目的','考虑周六延时未批准','成人可能20点少儿原时','desk/form feedback','no phone either','continuation attendance staffing'], 'CONVERSATIONAL','RESTRUCTURE','保持中文和英文分别成段。'),
 ('mx-research','MIXED','我们走访了两条街的十二家小店，不能代表整个城区。八位店主说雨天客流较少，但没有收集同期销售额。\n\nThe interviews do not establish a change in revenue. Some owners favored longer opening hours; others raised concerns about staffing. Counting visitors in different weather could help before discussing a trial.', ['两街十二店非全区','八人雨天客流少','无同期销售','no revenue established','support longerhours','staffconcerns','possibleweathercount beforetrial'], 'FORMAL','LIGHT','保留中英文各自承载的独立信息。'),
 ('mx-clean','MIXED','我在窗边等水烧开。楼下的小店开始搬出椅子。\n\nThe first customer sat down before the owner had finished wiping the table. I poured the tea and went back to my desk.', ['窗边等烧水','楼下搬椅子','customer before wiping finished','pouredtea','back desk'], 'NATURAL','LIGHT','保留双语叙事口吻。'),
 ('mx-long','MIXED','修理桌试办\n\n我们计划在旧阅览室试办一个月的周末修理桌，帮助居民就近处理小故障，也为邻里交流创造机会。志愿者提供共同检查和简单修理，不接收需要专业资质的电器维修。能否修好，取决于物件和当班志愿者经验。\n\nThe form asks for your name, time slot and item type. Your phone number is optional. Walk-ins are welcome, but bookings have priority during the booked slot. Late arrivals join the walk-in queue. Each person may register one item. Owners must buy any parts; volunteers will not advance the money.\n\n社区提供工具，参加者使用前应听取说明。儿童可以围观，使用工具须有监护人在旁。电池、漏液或破损物件是否接收，由当班负责人判断。以上安排旨在减少风险，不保证安全或修理成功。\n\nWe will record attendance and unfinished work each week. Residents may comment in person or anonymously. At the end of the month, volunteers will discuss whether to continue. Attendance alone will not decide it: space, tool wear and volunteers\' willingness also matter. We will review both goals, reducing early disposal and helping neighbors meet.', ['计划旧阅览室周末一月','附近小修邻里交流双目的','共同检查简单修理不接资质电器','成功看物件经验','form name slot item phoneoptional','walkins booking slotpriority latewalkin','oneitem ownersbuyparts noadvance','社区工具先说明','儿童围观用工具监护','电池漏液破损负责人判断','减险不保证','weeklyattendance unfinished','inpersonanonymous','endmonthdiscuss','not attendanceonly spacewearwillingness','review disposalneighbors dualgoals'], 'CONVERSATIONAL','RESTRUCTURE','保留双语结构、全部条件及两个目的，重组句子方便阅读。'),
]
for identifier, language, text, meanings, style, depth, requirements in holdouts:
    samples.append(sample(identifier, language, text, meanings, style, depth, requirements, 'expand'))
save('samples.json', samples)
jobs = []
rng = random.Random(161116)
for item in samples:
    strategies = ['text-polish-v3', 'text-polish-auto-v1']
    rng.shuffle(strategies)
    for strategy in strategies:
        jobs.append(dict(id=item['id']+'-'+strategy, phase=item['group'] if item['group']=='expand' else 'pilot',
                         sample=item['id'], language=item['language'], strategy=strategy,
                         input=dict(sourceText=item['text'], style=item['style'], depth=item['depth'], audience='',
                                    requirements=item['requirements'], lockedTexts=[], modelId='deepseek:deepseek-flash')))
paths = list(Path('services/api/src/main/java/com/promptoptimizer/textpolish').rglob('*.java'))
paths += list(Path('services/api/src/main/resources/textpolish').rglob('*.*'))
paths += list(Path('scripts/evaluation/text-polish-skills').glob('*.*'))
paths += [ROOT/'samples.json', ROOT/'metadata.json']
protocol = dict(maxActualCalls=60, phaseCaps=dict(pilot=16, expand=44), jobs=jobs,
    model='deepseek:deepseek-flash', temperature=0.2, maxTokens=8000, thinking='disabled',
    scope='单一当前模型，中文/英文/混合各自决定，开发模型内部匿名评阅，人工复核待完成',
    pilotCriteria='每语言至少一篇有具体阅读收益，自然文本允许持平，新增严重语义错误阻断该语言扩展。',
    adoptionCriteria='每语言留出至少两篇在两种用途有明确收益，胜多于负，且无新增严重事实、条件、承诺或独立含义错误。争议释义单列复核。',
    latencyCriteria='尚无产品绝对SLA，披露逐篇延迟和Token，不用开发代理自定倍数阻断。',
    stop='同机制两个候选出现相同严重失败则停止，本轮仅一个冻结候选，不做质量重试。',
    sourceSha256={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()})
save('protocol.json', protocol)
(ROOT / 'protocol.sha256').write_text(hashlib.sha256((ROOT/'protocol.json').read_bytes()).hexdigest() + '\n', encoding='utf-8')
save('authorization.json', dict(status='PENDING', proposedLimit=60,
    source='用户要求执行所引用评测方案；自动审批审查要求明确确认本轮预算、样本和DeepSeek目的地',
    historicalBatches='已关闭，不续用余额'))
save('ledger.json', dict(actualCalls=0, reserved=0, closed=False, entries=[]))
print('Frozen', len(samples), 'samples', len(jobs), 'jobs. No model calls.')
