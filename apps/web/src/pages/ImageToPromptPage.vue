<script setup lang="ts">
import { ElAlert, ElTag } from 'element-plus';

// 本页仅提供已确认的业务定义与使用指南；生成能力接入前不展示可操作的上传或模型按钮。
// 图像页面的 Plan 代表自定义新图创作，不能沿用文本工作台“只增加问答”的模式解释。
const modes = [
  {
    id: 'reference',
    title: '直接增强',
    state: 'Plan 关闭',
    direction: '从图到文 · 还原参考图',
    description: '上传参考图片，将画面中的主体、风格、构图、光照、色彩和可见文字整理成描述性提示词。',
    suitable: '已有图片，希望提取画面特征、整理表达，或为相近视觉效果准备提示词。',
    input: '需要一张参考图。',
    boundary: '不发起创作问答，不主动新增对象、替换文案或改变场景。看不清的内容保留不确定性。',
    example: '例如：把一张蓝白配色的产品海报，整理成描述其主体、留白与版式的提示词。',
    steps: [
      { title: '关闭 Plan，选择参考图', description: '选择清晰的图片并核对本地预览；上传前检查是否含有需要遮挡的私人信息。' },
      { title: '提取画面特征', description: '点击“提取参考图提示词”，由视觉模型描述图片中实际可见的内容，无需回答创作问题。' },
      { title: '核对并复制', description: '检查对象、文字和布局描述，再将提示词复制到支持文生图的工具中使用。' },
    ],
  },
  {
    id: 'creation',
    title: 'Plan 创作',
    state: 'Plan 开启',
    direction: '从无到有 · 构思全新图片',
    description: '从你的创作构想出发，通过必要的确认问题厘清画面内容、风格偏好和细节，形成指导 AI 创作新图的提示词。',
    suitable: '希望创作新的海报、插画、卡片或流程图，需要一起明确主题、氛围、文案和布局。',
    input: '先写创作构想；参考图可选，仅作为你指定的灵感或局部参考。',
    boundary: '以你的创作意图和实际回答为依据，不以复刻素材为目标；已有明确要求不重复问，信息充分时可以零问题继续。',
    example: '例如：从“给读书会设计一张温暖的招募海报”开始，确认插画风格、标题和画面布局。',
    steps: [
      { title: '开启 Plan，描述创作构想', description: '说明想创作什么、用于哪里；有灵感图片时可附上，并写清借鉴风格、配色还是布局。' },
      { title: '回答影响画面的关键问题', description: '确认主题、风格、氛围和必要细节。需要出现的文案单独填写；也可以明确委托 AI 提出设计。' },
      { title: '确认创作方向并使用', description: '核对确认摘要，生成新图提示词。检查后编辑、复制，交给下游绘图工具创作。' },
    ],
  },
] as const;
</script>

<template>
  <section class="image-prompt-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">Image to prompt</span>
        <h1>图像转提示词</h1>
        <p>已有参考图，提取画面描述；有新的创作想法，用 Plan 一起明确画面。</p>
      </div>
      <ElTag type="info" effect="plain">模式说明与使用指南</ElTag>
    </header>

    <ElAlert
      title="生成功能准备中"
      description="当前可阅读两种模式的定义与使用步骤，图片上传、创作问答和提示词生成尚未开放。"
      type="info"
      :closable="false"
      show-icon
    />

    <section aria-labelledby="image-modes-title">
      <div class="section-heading">
        <h2 id="image-modes-title">选择适合你的模式</h2>
        <p>Plan 开关决定创作目标：关闭时还原参考图，开启时辅助自定义新图创作。</p>
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
        <h2 id="image-how-to-title">如何使用图像转提示词</h2>
        <p>以下为功能开放后的使用流程，请先按目标选择模式。</p>
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

    <aside class="glass-panel usage-notes" aria-labelledby="image-notes-title">
      <h2 id="image-notes-title">使用前了解</h2>
      <ul>
        <li>两种模式都输出提示词，由你复制到下游绘图工具使用，本页面不直接生成图片。</li>
        <li>“复刻”指描述性还原可见特征，不保证像素级一致；细小文字和复杂关系需要核对。</li>
        <li>想更换主体、增加文案或重新设计场景，请使用 Plan 创作；直接增强专注描述参考图。</li>
        <li>Plan 有参考图时，仅采用你明确指定的参考内容；创作要求不会改写原图观察事实。</li>
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
