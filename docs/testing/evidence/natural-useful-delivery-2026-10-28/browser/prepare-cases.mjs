import { writeFile } from 'node:fs/promises';
import { cases } from '../fixtures/project.mjs';

const samples = [...cases,
  { id: 'Q01', rawPrompt: '请帮我查询全球使用AI最多的职业/行业', useProject: false },
  { id: 'V01', rawPrompt: '只输出三行：第一行是1，第二行是2，第三行是3，不要其他内容。', useProject: false },
  { id: 'V02', rawPrompt: '请只阅读 @synthetic-survey-workspace/src/views/records 的新增与导入实现，熟悉调用关系后等待我给具体任务，不修改代码，也不交付说明文档。', useProject: true },
  { id: 'V03', rawPrompt: '没有相关资料，请解释为什么同一张照片在不同屏幕上颜色不一样，并给普通用户可操作的排查建议。', useProject: false },
];
await writeFile(new URL('./cases.json', import.meta.url), JSON.stringify({ syntheticOnly: true,
  role: '开发与已知失败正常变体；未作为独立留出', cases: samples }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ cases: samples.map(item => item.id), modelCalls: 0 }));
