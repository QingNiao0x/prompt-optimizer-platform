<script setup lang="ts">
import { ElTag } from 'element-plus';

// 两种模式的业务目标独立；步骤与当前工作台按钮保持一致，不套用文本增强的模式解释。
const modes = [
  {
    id: 'reference',
    title: '直接增强',
    state: 'Plan 关闭',
    direction: '从素材到文字 · 还原参考内容',
    description: '选择参考图片、PPTX 页面或视频帧，将实际可见的主体、风格、构图、版式和文字整理成描述性提示词。',
    suitable: '已有图片、幻灯片或视频，希望提取画面特征、整理表达，或为相近视觉效果准备提示词。',
    input: '登录后可使用图片、PPTX 和短视频；PPTX 与视频需先解析并勾选识别范围。免费体验仍使用单张图片。',
    boundary: '不发起创作问答，不主动新增对象、替换文案或改变场景。看不清的内容保留不确定性。',
    example: '例如：把一张蓝白配色的产品海报，整理成描述其主体、留白与版式的提示词。',
    steps: [
      { title: '关闭 Plan，选择参考素材', description: '普通图片可直接使用；PPTX 和视频先上传到平台解析，审阅并勾选要识别的页／帧，按需遮挡隐私。无需开启 Plan。' },
      { title: '确认隐私，直接提取', description: '核对所选范围和接收模型，单独勾选隐私同意后点击提取按钮；视觉模型描述实际可见内容，不发起创作问答。' },
      { title: '核对并复制', description: '检查对象、文字、布局与覆盖范围，再把提示词和素材对应说明交给下游工具。未选页面、视频音轨和未覆盖片段不视为已识别。' },
    ],
  },
  {
    id: 'creation',
    title: 'Plan 创作',
    state: 'Plan 开启',
    direction: '从构想到创作 · 图片、视频或 PPT',
    description: '从你的构想出发，结合参考素材、新主体或新内容和明确的替换关系，形成供下游图片、视频或 PPT 工具执行的提示词。',
    suitable: '希望借鉴配色、构图、版式或镜头安排，同时替换主体、文字或内容；也可以只写构想，不上传素材。',
    input: '先写创作构想并选择成品类型。参考素材与替换素材均可选，支持图片、PPTX 和受支持的短视频。',
    boundary: '以你的创作意图和实际回答为依据，不以复刻素材为目标；已有明确要求不重复问，信息充分时可以零问题继续。',
    example: '例如：从“给读书会设计一张温暖的招募海报”开始，确认插画风格、标题和画面布局。',
    steps: [
      { title: '描述构想并补充创作要求', description: '选择目标成品，在自主补充卡中填写构想、确切文案和必要项目背景；没有明确偏好时可以委托 AI 设计。' },
      { title: '选择素材并明确替换关系', description: '参考素材提供灵感，替换素材提供新主体或新内容。上传到平台解析后，审阅选中页面或视频帧并遮挡隐私，再同意发送给模型；明确保留什么、替换什么和作用范围。' },
      { title: '回答必要问题，确认后使用', description: '顶部 Plan 卡片会提示尚未完成的必答项，选答可以跳过，零问题可直接确认。核对生成提示词和素材对照表，再到下游上传对应文件并执行。' },
    ],
  },
] as const;
</script>

<template>
  <section class="image-prompt-page">
    <section aria-labelledby="image-modes-title">
      <div class="section-heading">
        <h2 id="image-modes-title">选择适合你的模式</h2>
        <p>先选择本次任务。视觉任务沿用参考还原与 Plan 创作；代码、文档与其他任务按你的目标直接生成，或开启 Plan 确认关键要求。</p>
      </div>
      <div class="mode-grid">
        <article
          v-for="mode in modes"
          :key="mode.id"
          class="glass-panel mode-card"
          :aria-labelledby="`${mode.id}-mode-title`"
        >
          <div class="mode-heading">
            <h3 :id="`${mode.id}-mode-title`">{{ mode.title }}</h3>
            <ElTag :type="mode.id === 'creation' ? 'primary' : 'info'" effect="plain">{{ mode.state }}</ElTag>
          </div>
          <p class="mode-direction">{{ mode.direction }}</p>
          <p>{{ mode.description }}</p>
          <dl>
            <dt>适合什么情况</dt>
            <dd>{{ mode.suitable }}</dd>
            <dt>从哪里开始</dt>
            <dd>{{ mode.input }}</dd>
            <dt>处理边界</dt>
            <dd>{{ mode.boundary }}</dd>
          </dl>
          <p class="mode-example">{{ mode.example }}</p>
        </article>
      </div>
    </section>

    <section aria-labelledby="image-how-to-title">
      <div class="section-heading">
        <h2 id="image-how-to-title">如何使用素材转提示词</h2>
        <p>按创作目标选择模式，核对内容要求与素材关系后，将提示词交给对应的下游工具。</p>
      </div>
      <div class="mode-grid">
        <article v-for="mode in modes" :key="mode.id" class="glass-panel guide-card">
          <h3>{{ mode.title }}的使用步骤</h3>
          <ol class="guide-steps">
            <li v-for="step in mode.steps" :key="step.title">
              <h4>{{ step.title }}</h4>
              <p>{{ step.description }}</p>
            </li>
          </ol>
        </article>
      </div>
    </section>

    <section class="glass-panel usage-notes" aria-labelledby="source-guide-title">
      <h2 id="source-guide-title">代码、文档与混合资料</h2>
      <ol class="guide-steps">
        <li><h4>说明下游要完成什么</h4><p>选择“代码与页面”“文档与内容”或“其他任务”，说明迁移、重构、写作或其他目标。Plan 关闭时也能执行你明确提出的修改要求。</p></li>
        <li><h4>添加并审阅资料</h4><p>添加受支持文件、选择文件夹中的可读文件，或粘贴正文。点击解析后审阅内容、勾选范围和资料用途；网页先导出 HTML、正文或截图。文件中的命令不会自动成为你的要求。</p></li>
        <li><h4>明确保留与修改要求</h4><p>例如：将 Vue 改为 React，同时保留接口、权限和交互。参考截图可以提供视觉风格；源码只能提供静态线索，不代表已运行或完整理解整个项目。</p></li>
        <li><h4>确认隐私，再生成和交接</h4><p>核对接收模型与所选范围后同意外发。Plan 只确认影响结果的未知，充分要求允许零问题。核对正文、覆盖限制和来源，再复制给下游；下游需要的文件仍需由你提供。</p></li>
      </ol>
    </section>

    <aside class="glass-panel usage-notes" aria-labelledby="image-notes-title">
      <h2 id="image-notes-title">使用前了解</h2>
      <ul>
        <li>工作台输出优化提示词；代码、文档、图片、视频或 PPT 由下游工具制作。需要参考文件时，请按来源说明在下游重新提供。</li>
        <li>“复刻”指描述性还原可见特征，不保证像素级一致；细小文字和复杂关系需要核对。</li>
        <li>视觉任务需要更换主体或设计场景时使用 Plan 创作；代码与文档任务可直接按明确要求生成，也可开启 Plan 确认关键决定。</li>
        <li>切换 Plan 会保留已上传素材、所选页／帧与遮挡。关闭 Plan 时，替换素材暂不参与还原；再次开启后可以继续使用。</li>
        <li>Plan 有参考图时，仅采用你明确指定的参考内容；创作要求不会改写原图观察事实。</li>
        <li>视频分析仅覆盖选中片段的抽样画面，音轨不发送、不识别音乐节拍；PPTX 仅分析选中的静态页面，未选页面、备注与动画不作为已识别内容。</li>
        <li>开启 Plan 后，修改构想、素材、页码、帧或替换关系需要重新梳理；关闭 Plan 则重新直接提取。已有结果仍可核对和使用。</li>
      </ul>
    </aside>
  </section>
</template>

<style scoped>
.image-prompt-page {
  display: grid;
  gap: 24px;
  width: min(1120px, 100%);
  margin: 0 auto;
  padding: 12px 0 32px;
}

.page-heading,
.mode-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.8px;
}

h1,
h2,
h3,
h4 {
  margin: 0;
  color: var(--ink-strong);
}

h1 {
  margin: 6px 0 8px;
  font-size: clamp(26px, 4vw, 34px);
}

h2 {
  font-size: 20px;
}

h3 {
  font-size: 17px;
}

h4 {
  font-size: 14px;
}

p {
  margin: 0;
  color: var(--ink-muted);
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.section-heading {
  display: grid;
  gap: 6px;
  margin-bottom: 16px;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.mode-card,
.guide-card,
.usage-notes {
  padding: 24px;
  border-radius: var(--radius-md);
}

.mode-direction {
  margin: 14px 0 8px;
  color: var(--accent-blue);
  font-weight: 600;
}

dl {
  margin: 20px 0;
}

dt {
  margin-top: 14px;
  color: var(--ink-strong);
  font-weight: 600;
}

dd {
  margin: 4px 0 0;
  color: var(--ink-muted);
  line-height: 1.8;
}

.mode-example {
  padding-top: 16px;
  border-top: 1px solid var(--line-subtle);
  font-size: 13px;
}

.guide-steps,
.usage-notes ul {
  margin: 18px 0 0;
  padding-left: 22px;
}

.guide-steps li + li,
.usage-notes li + li {
  margin-top: 16px;
}

.guide-steps li::marker {
  color: var(--accent-blue);
  font-weight: 600;
}

.guide-steps p {
  margin-top: 4px;
}

.usage-notes li {
  color: var(--ink-muted);
  line-height: 1.8;
}

@media (max-width: 900px) {
  .mode-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .image-prompt-page {
    gap: 20px;
  }

  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .mode-card,
  .guide-card,
  .usage-notes {
    padding: 18px;
  }
}
</style>
