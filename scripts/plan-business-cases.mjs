/** 固定合成业务材料；所填答案是验收夹具，不能代表真实用户做出的业务决定。 */
export const approvalConflict = {
  id: 'business_resolved_approval_conflict', category: 'conflict',
  rawPrompt: '根据新审批方案实现订单审批；审批阈值冲突必须先确认，保持现有接口兼容，不增加其他业务范围。',
  contextDescription: 'Java 21、Spring Boot 3 订单服务', expectedTemplate: 'FEATURE_DEVELOPMENT',
  files: [
    { path: 'src/rules/审批规则.txt', language: 'text', content: '审批阈值：三万元\n订单审批必须记录审批人及审批时间。' },
    { path: 'docs/新审批方案.txt', language: 'text', content: '审批阈值：五万元\n超过审批阈值的订单必须由财务复核。' },
  ],
  mustAsk: [/审批阈值|不同取值/], finalTerms: ['五万元', '财务复核'],
};

export const newlyRetrievedApprovalRule = {
  path: 'docs/财务补充规则.txt', language: 'text',
  content: '审批阈值：八万元\n财务补充规则与旧审批方案存在不同取值，必须确认本次适用的规则。',
};

export const notificationChoice = {
  id: 'business_answer_changes_retrieval', category: 'software',
  rawPrompt: '为订单审批接入通知能力。现有工程同时包含邮件通知和站内信实现，本次只选一个通知渠道，尚未决定。请先询问通知渠道，再基于我选择的渠道生成实施提示词。',
  contextDescription: 'Java 21、Spring Boot 3 订单服务；现有接口应保持兼容。',
  expectedTemplate: 'FEATURE_DEVELOPMENT',
  files: [{ path: 'docs/订单通知需求.txt', language: 'text', content: '订单审批通过后发送通知；邮件和站内信是两个已有能力，本次接入渠道尚未确定。不得同时启用两个渠道。' }],
  mustAsk: [/渠道|邮件|站内信/], finalTerms: ['订单', '通知'],
};

export const notificationCorpus = [
  { path: 'src/notification/email/邮件通知实现.java', language: 'java', content: '// 订单审批邮件通知。邮件通知规则：必须使用业务幂等键防止重复发送邮件。\nclass EmailApprovalNotifier { void notifyApprovedOrder(String orderId) {} }' },
  { path: 'docs/邮件通知配置.md', language: 'markdown', content: '# 邮件通知配置\n邮件通知必须重试三次，仍失败时记录可追踪失败状态；不回滚已通过的订单审批。' },
  { path: 'src/notification/inbox/站内信通知实现.java', language: 'java', content: '// 订单审批站内信通知。站内信通知规则：必须按用户隔离读取权限。\nclass InboxApprovalNotifier { void notifyApprovedOrder(String orderId) {} }' },
  { path: 'docs/站内信通知配置.md', language: 'markdown', content: '# 站内信通知配置\n站内信通知必须维护已读状态；不得查询其他用户的站内信。' },
];

export const frontendMigration = {
  id: 'business_current_react_target_vue', category: 'software',
  rawPrompt: '重构现有 React 前端并迁移到 Vue 3。本次只交付迁移方案，不执行代码修改。保持现有后端接口、鉴权行为和页面路由兼容，不将 React 写成迁移后的目标技术。',
  contextDescription: '前端当前采用 React 18 和 TypeScript；后端接口无需改动。',
  expectedTemplate: 'REFACTORING',
  files: [
    { path: 'package.json', language: 'json', content: '{"dependencies":{"react":"18.3.1","react-dom":"18.3.1"},"devDependencies":{"typescript":"5.7.3"}}' },
    { path: 'docs/前端现状.md', language: 'markdown', content: '# 前端现状\n当前框架：React 18\n前端迁移必须保持后端接口、鉴权行为和已有页面路由兼容。' },
  ],
  mustNotAsk: [/目标框架.*(?:什么|哪)|迁移到哪|当前.*框架.*(?:什么|哪)/],
  finalTerms: ['React', 'Vue 3', '鉴权', '路由'],
};
