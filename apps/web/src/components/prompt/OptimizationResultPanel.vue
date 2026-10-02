<script setup lang="ts">
import {
  ArrowDown,
  Check,
  CopyDocument,
  DataAnalysis,
  EditPen,
  RefreshRight,
  Right,
  Share,
  TopRight,
  WarningFilled,
} from '@element-plus/icons-vue';
import { ElButton, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu, ElInput, ElLink, ElMessage } from 'element-plus';
import type { InputInstance } from 'element-plus';
import { computed, onBeforeUnmount, ref, toRefs, watch } from 'vue';

import ResultCard from '@/components/prompt/ResultCard.vue';
import ResultMetaBar from '@/components/prompt/ResultMetaBar.vue';
import { reportClientAnalyticsEventBestEffort } from '@/services/adminAnalyticsApi';
import type { OptimizationResult, PromptSection, PromptSectionType } from '@/types/api';

interface Props {
  result?: OptimizationResult;
  busy: boolean;
  planModeEnabled: boolean;
}

interface Emits {
  (event: 'save', sections: PromptSection[]): void;
  (event: 're-enhance'): void;
}

const COPY_PLATFORM_STORAGE_KEY = 'prompt-optimizer.copy-platform.v1';
// 平台地址只来自固定名单；跳转不携带提示词，也不尝试自动填写或发送。
const COPY_PLATFORMS = [
  { name: 'DeepSeek', url: 'https://chat.deepseek.com/' },
  { name: 'Kimi', url: 'https://www.kimi.com/' },
  { name: '智谱', url: 'https://chatglm.cn/' },
  { name: '元宝', url: 'https://yuanbao.tencent.com/' },
  { name: '豆包', url: 'https://www.doubao.com/chat/' },
] as const;
type CopyPlatform = typeof COPY_PLATFORMS[number];

/** 本机只记住平台名，存储不可用或值不在名单中时仍可正常复制。 */
const loadPreferredPlatform = (): CopyPlatform => {
  try {
    const name = localStorage.getItem(COPY_PLATFORM_STORAGE_KEY);
    return COPY_PLATFORMS.find((platform) => platform.name === name) ?? COPY_PLATFORMS[0];
  } catch {
    return COPY_PLATFORMS[0];
  }
};

const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const { result } = toRefs(props);
const editing = ref(false);
const copying = ref(false);
const sharing = ref(false);
const copied = ref(false);
const lastCopiedPrompt = ref<string>();
const preferredPlatform = ref<CopyPlatform>(loadPreferredPlatform());
const manualCopyVisible = ref(false);
const manualCopyContent = ref('');
const manualCopyInput = ref<InputInstance>();
let copyFeedbackTimer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
const ambiguitiesOpen = ref(false);
const warningsOpen = ref(false);
const draftSections = ref<PromptSection[]>([]);
const copyDisabled = computed(() =>
  props.busy || editing.value || copying.value || sharing.value || !result.value?.optimizedPrompt.trim(),
);
const canOpenPlatform = computed(() =>
  !copyDisabled.value && lastCopiedPrompt.value !== undefined
  && lastCopiedPrompt.value === result.value?.optimizedPrompt,
);
const shareData = computed(() => ({ title: '优化提示词', text: result.value?.optimizedPrompt ?? '' }));
const canSharePrompt = computed(() => {
  if (!window.isSecureContext || !shareData.value.text.trim()
    || typeof navigator.share !== 'function' || typeof navigator.canShare !== 'function') return false;
  try {
    return navigator.canShare(shareData.value);
  } catch {
    return false;
  }
});

const displaySections = computed(() =>
  result.value?.sections.filter((section) => section.type !== 'CLARIFICATIONS') ?? [],
);

const ambiguityPreview = computed(() => result.value?.ambiguities[0] ?? '');
const warningPreview = computed(() => result.value?.warnings?.[0] ?? '');

const SECTION_LABELS: Record<PromptSectionType, string> = {
  BACKGROUND: '背景',
  TASK: '任务',
  OUTPUT: '输出',
  CONSTRAINTS: '约束',
  CLARIFICATIONS: '待确认',
  ACCEPTANCE: '验收',
  EXAMPLES: '示例',
};

const resetCopyFeedback = (): void => {
  clearTimeout(copyFeedbackTimer);
  copied.value = false;
  lastCopiedPrompt.value = undefined;
  manualCopyVisible.value = false;
  manualCopyContent.value = '';
};

/** 异步权限操作可能晚于编辑、重新生成或离开页面，不把旧结果的反馈显示到新结果上。 */
const isCurrentExport = (source: OptimizationResult | undefined): boolean =>
  !disposed && result.value === source && !props.busy && !editing.value;

const showManualCopy = (content: string): void => {
  manualCopyContent.value = content;
  manualCopyVisible.value = true;
};

/** 只读文本框保留整份原文；选择文本不等于成功写入剪贴板，因此不记录导出事件。 */
const selectManualPrompt = (): void => {
  const input = manualCopyInput.value?.textarea;
  if (!input) return;
  input.focus();
  input.select();
  input.setSelectionRange(0, input.value.length);
  input.scrollTop = 0;
};

/** 所有复制入口共用剪贴板处理；只有写入成功才记录导出，不把平台选择当作已发送。 */
const copyPrompt = async (content: string, platform?: CopyPlatform): Promise<void> => {
  if (copyDisabled.value || !content.trim()) return;
  const source = result.value;
  resetCopyFeedback();
  copying.value = true;
  try {
    await navigator.clipboard.writeText(content);
  } catch {
    if (isCurrentExport(source)) {
      ElMessage.error('复制失败，请手动选择文本复制。');
      showManualCopy(content);
    }
    return;
  } finally {
    copying.value = false;
  }
  reportClientAnalyticsEventBestEffort('RESULT_EXPORTED');
  if (!isCurrentExport(source)) return;
  copied.value = true;
  lastCopiedPrompt.value = content;
  copyFeedbackTimer = setTimeout(() => { copied.value = false; }, 2000);
  if (platform) {
    preferredPlatform.value = platform;
    try {
      localStorage.setItem(COPY_PLATFORM_STORAGE_KEY, platform.name);
    } catch {
      // 浏览器禁用存储时只保留本次页面偏好，不影响已经完成的复制。
    }
  }
  ElMessage.success(platform
    ? `已复制到剪贴板，请前往 ${platform.name} 粘贴。`
    : '已复制到剪贴板。');
};

/** 读取当前已保存的完整结果；生成中或编辑中的内容不从平台入口导出。 */
const copyToPlatform = async (name: CopyPlatform['name']): Promise<void> => {
  if (!result.value || props.busy || editing.value) return;
  const platform = COPY_PLATFORMS.find((candidate) => candidate.name === name);
  if (!platform) return;
  await copyPrompt(result.value.optimizedPrompt, platform);
};

const copyToDeepSeek = (): Promise<void> => copyToPlatform('DeepSeek');
const copyToKimi = (): Promise<void> => copyToPlatform('Kimi');
const copyToZhipu = (): Promise<void> => copyToPlatform('智谱');
const copyToYuanbao = (): Promise<void> => copyToPlatform('元宝');
const copyToDoubao = (): Promise<void> => copyToPlatform('豆包');

const platformCopyActions = [
  { name: 'DeepSeek', copy: copyToDeepSeek },
  { name: 'Kimi', copy: copyToKimi },
  { name: '智谱', copy: copyToZhipu },
  { name: '元宝', copy: copyToYuanbao },
  { name: '豆包', copy: copyToDoubao },
];

/** 菜单只接受已列出的平台名，各入口仍复用同一剪贴板与导出事件流程。 */
const handlePlatformCopy = (platformName: unknown): void => {
  void platformCopyActions.find((platform) => platform.name === platformName)?.copy();
};

/** 按当前内容检测系统分享能力；取消不报错，失败提供完整文本，成功只表示交给系统处理。 */
const sharePrompt = async (): Promise<void> => {
  if (copyDisabled.value || !canSharePrompt.value) return;
  const source = result.value;
  const data = shareData.value;
  sharing.value = true;
  try {
    // 直接在点击处理内调用，避免额外异步操作消耗浏览器要求的用户激活。
    await navigator.share(data);
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
    if (isCurrentExport(source)) {
      ElMessage.error('分享未完成，请复制提示词后自行粘贴。');
      showManualCopy(data.text);
    }
    return;
  } finally {
    sharing.value = false;
  }
  reportClientAnalyticsEventBestEffort('RESULT_EXPORTED');
  if (isCurrentExport(source)) ElMessage.success('已交给系统分享，请在目标应用中确认。');
};

watch(result, () => {
  resetCopyFeedback();
  editing.value = false;
  ambiguitiesOpen.value = false;
  warningsOpen.value = false;
  draftSections.value = [];
});

watch([() => props.busy, editing], ([busy, isEditing]) => {
  if (busy || isEditing) resetCopyFeedback();
});

onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(copyFeedbackTimer);
});

const toggleAmbiguities = (): void => {
  ambiguitiesOpen.value = !ambiguitiesOpen.value;
};

const startEditing = (): void => {
  if (!result.value) {
    return;
  }
  draftSections.value = result.value.sections
    .filter((section) => section.type !== 'CLARIFICATIONS')
    .map((section) => ({ ...section }));
  ambiguitiesOpen.value = false;
  warningsOpen.value = false;
  editing.value = true;
};

const cancelEditing = (): void => {
  editing.value = false;
  draftSections.value = [];
};

const saveEditing = (): void => {
  emit('save', draftSections.value.map((section) => ({ ...section })));
  editing.value = false;
};
</script>

<template>
  <section class="result-panel">
    <div class="result-heading">
      <span class="step-label">03 / Structured prompt</span>
      <div v-if="result" class="result-actions">
        <template v-if="editing">
          <ElButton size="small" :disabled="busy" @click="cancelEditing">取消</ElButton>
          <ElButton size="small" type="primary" :disabled="busy" @click="saveEditing">
            保存修改
          </ElButton>
        </template>
        <template v-else>
          <div class="copy-control" role="group" aria-label="复制提示词">
            <ElButton
              class="copy-main-button"
              type="primary"
              size="small"
              :icon="copied ? Check : CopyDocument"
              :loading="copying"
              :disabled="copyDisabled"
              @click="copyPrompt(result.optimizedPrompt)"
            >
              <span aria-live="polite">{{ copied ? '已复制' : '复制提示词' }}</span>
            </ElButton>
            <ElDropdown
              trigger="click"
              placement="bottom-end"
              :disabled="copyDisabled"
              @command="handlePlatformCopy"
            >
              <ElButton
                class="copy-platform-trigger"
                type="primary"
                size="small"
                :icon="ArrowDown"
                :disabled="copyDisabled"
                aria-label="选择 AI 平台"
                title="选择 AI 平台"
              />
              <template #dropdown>
                <ElDropdownMenu class="platform-copy-menu">
                  <ElDropdownItem
                    v-for="platform in platformCopyActions"
                    :key="platform.name"
                    :command="platform.name"
                    :disabled="copyDisabled"
                  >
                    复制至 {{ platform.name }}
                  </ElDropdownItem>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </div>
          <ElButton size="small" :icon="EditPen" :disabled="busy" @click="startEditing">
            编辑
          </ElButton>
        </template>
      </div>
    </div>

    <h2>增强结果</h2>

    <ElButton
      v-if="result && !editing"
      class="re-enhance-button"
      :icon="RefreshRight"
      size="small"
      :loading="busy"
      @click="emit('re-enhance')"
    >
      {{ planModeEnabled ? '先确认并再次增强' : '直接再次增强' }}
    </ElButton>

    <div v-if="result && !editing" class="export-guidance">
      <p class="platform-copy-hint">点击复制后，请将内容粘贴至您使用的 AI 软件或网页版。</p>
      <div v-if="canOpenPlatform || canSharePrompt" class="export-followup">
        <ElLink
          v-if="canOpenPlatform"
          class="open-platform-link"
          :href="preferredPlatform.url"
          target="_blank"
          rel="noopener noreferrer"
          type="primary"
          :underline="false"
          :icon="TopRight"
        >
          打开 {{ preferredPlatform.name }}
        </ElLink>
        <ElButton
          v-if="canSharePrompt"
          class="share-prompt-button"
          size="small"
          text
          :icon="Share"
          :loading="sharing"
          :disabled="copyDisabled"
          @click="sharePrompt"
        >
          系统分享
        </ElButton>
      </div>
    </div>

    <div v-if="!result" class="empty-result">
      <div class="signal-flow" aria-hidden="true">
        <div class="signal-source">
          <span>意图</span>
          <span>上下文</span>
          <span>约束</span>
        </div>
        <Right />
        <div class="signal-target">
          <DataAnalysis />
          <span>结构化提示词</span>
        </div>
      </div>
      <h3>结果会在这里展开</h3>
      <p>
        输入一个简短需求并点击“{{ planModeEnabled ? '先确认并增强' : '直接增强提示词' }}”，
        系统会结合背景与资料生成可直接使用的任务说明。
      </p>
    </div>

    <div v-else class="result-stage">
      <div
        v-if="result.warnings?.length && !editing"
        class="context-warning"
        :class="{ 'is-open': warningsOpen }"
      >
        <button
          type="button"
          class="context-warning-toggle"
          :aria-expanded="warningsOpen"
          aria-controls="context-warning-details"
          :aria-label="`上下文分析提醒，${result.warnings.length} 项`"
          @click="warningsOpen = !warningsOpen"
        >
          <span class="context-warning-title">
            <WarningFilled aria-hidden="true" />
            <strong>上下文分析提醒</strong>
            <span>{{ result.warnings.length }} 项</span>
          </span>
          <ArrowDown class="context-warning-chevron" :class="{ 'is-open': warningsOpen }" aria-hidden="true" />
          <span v-if="!warningsOpen" class="context-warning-preview">{{ warningPreview }}</span>
        </button>
        <ul v-if="warningsOpen" id="context-warning-details">
          <li v-for="(warning, index) in result.warnings" :key="`${index}-${warning}`">{{ warning }}</li>
        </ul>
      </div>

      <div
        v-if="result.ambiguities.length && !editing"
        class="ambiguity-note"
        :class="{ 'is-open': ambiguitiesOpen }"
      >
        <button
          type="button"
          class="ambiguity-toggle"
          :aria-expanded="ambiguitiesOpen"
          aria-controls="ambiguity-details"
          :aria-label="`待确认事项，${result.ambiguities.length} 项，需要人工核对`"
          @click="toggleAmbiguities"
        >
          <span class="ambiguity-title" role="status">
            <WarningFilled aria-hidden="true" />
            <strong>待确认事项</strong>
            <span>{{ result.ambiguities.length }} 项</span>
          </span>
          <span class="ambiguity-meta">
            <small>需要人工核对</small>
            <ArrowDown class="ambiguity-chevron" :class="{ 'is-open': ambiguitiesOpen }" aria-hidden="true" />
          </span>
          <span v-if="!ambiguitiesOpen" class="ambiguity-preview">{{ ambiguityPreview }}</span>
        </button>
        <div v-show="ambiguitiesOpen" id="ambiguity-details" class="ambiguity-body">
          <p class="ambiguity-summary">
            这些信息尚未经过方案确认。开启 Plan 确认后再次增强，系统会逐项向你提问。
          </p>
          <ul class="ambiguity-list">
            <li v-for="(item, index) in result.ambiguities" :key="`${index}-${item}`">{{ item }}</li>
          </ul>
        </div>
      </div>

      <div
        class="result-content"
        tabindex="0"
        aria-label="增强结果内容，可滚动查看完整提示词"
      >
        <ResultMetaBar :result="result" />

        <article v-if="!editing" class="section-list">
          <ResultCard
            v-for="(section, index) in displaySections"
            :key="section.type"
            :section="section"
            :index="index"
            :is-last="index === displaySections.length - 1"
          />
        </article>

        <div v-else class="edit-section-list">
          <section v-for="section in draftSections" :key="section.type" class="edit-section">
            <label :for="`section-${section.type}`">
              {{ SECTION_LABELS[section.type] }} · {{ section.title }}
            </label>
            <ElInput
              :id="`section-${section.type}`"
              v-model="section.content"
              type="textarea"
              :rows="6"
              resize="vertical"
              maxlength="12000"
              show-word-limit
            />
          </section>
        </div>
      </div>
    </div>

    <ElDialog
      v-model="manualCopyVisible"
      class="manual-copy-dialog"
      title="手动复制提示词"
      width="min(640px, calc(100vw - 24px))"
      align-center
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
      @opened="selectManualPrompt"
    >
      <p id="manual-copy-instructions" class="manual-copy-instructions">
        完整提示词已保留在下方。点击“全选”后，电脑可按 Ctrl+C / ⌘C，手机可长按文本选择“复制”。
      </p>
      <ElInput
        ref="manualCopyInput"
        class="manual-copy-input"
        :model-value="manualCopyContent"
        type="textarea"
        :rows="12"
        readonly
        resize="none"
        aria-label="完整提示词，供手动复制"
        aria-describedby="manual-copy-instructions"
      />
      <template #footer>
        <div class="manual-copy-actions">
          <ElButton @click="manualCopyVisible = false">关闭</ElButton>
          <ElButton type="primary" @click="selectManualPrompt">全选</ElButton>
        </div>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.result-panel {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
  padding: 22px 20px 18px;
}

.result-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.step-label {
  flex: 1 1 140px;
  padding-top: 4px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.8px;
  text-transform: uppercase;
}

.result-actions {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 5px;
}

.result-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.result-actions > :deep(.el-button) {
  padding: 5px 9px;
  border-color: var(--glass-border-subtle);
  color: var(--text-secondary);
  background: var(--glass-bg-subtle);
}

.copy-control {
  display: inline-flex;
  align-items: stretch;
}

.copy-control :deep(.el-button) {
  min-height: 32px;
  touch-action: manipulation;
}

.copy-main-button {
  min-width: 104px;
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}

.copy-platform-trigger {
  min-width: 32px;
  margin-left: -1px;
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}

.platform-copy-menu :deep(.el-dropdown-menu__item) {
  min-width: 160px;
  min-height: 44px;
  touch-action: manipulation;
}

h2 {
  margin: 8px 0 12px;
  color: var(--text-primary);
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
}

.re-enhance-button {
  align-self: flex-start;
  margin-bottom: 18px;
  border-color: var(--accent-border);
  border-radius: 999px;
  color: var(--accent);
  background: var(--accent-soft);
}

.empty-result {
  display: grid;
  min-height: 0;
  flex: 1;
  place-content: center;
  justify-items: center;
  padding: 12px 6px 8px;
  text-align: center;
}

.signal-flow {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.signal-flow > svg {
  width: 18px;
  color: var(--accent);
}

.signal-source {
  display: grid;
  grid-template-columns: repeat(3, auto);
  gap: 4px;
}

.signal-source span {
  padding: 6px 10px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 8px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--glass-bg-subtle);
}

.signal-target {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 11px;
  border: 1px solid var(--accent-border);
  border-radius: 10px;
  color: var(--text-primary);
  font-size: 12px;
  background: var(--accent-soft);
}

.signal-target svg {
  width: 15px;
  color: var(--accent);
}

.empty-result h3 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 17px;
}

.empty-result p {
  max-width: 380px;
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.result-stage {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.result-content {
  min-width: 0;
  min-height: 0;
  flex: 1 1 58%;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 5px 8px 0;
  scrollbar-gutter: stable;
  overscroll-behavior: contain;
}

.result-content:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent) 72%, transparent);
  outline-offset: 3px;
}

.export-guidance {
  margin: 0 0 12px;
}

.platform-copy-hint,
.manual-copy-instructions {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.export-followup {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-top: 4px;
}

.open-platform-link,
.share-prompt-button {
  min-height: 32px;
  touch-action: manipulation;
}

.manual-copy-instructions {
  margin-bottom: 12px;
}

/* 弹窗挂到 body，专属类避免手机端固定外边距把内容区拉满屏幕。 */
:global(.manual-copy-dialog) {
  align-self: center;
}

.manual-copy-input :deep(.el-textarea__inner) {
  max-height: 45vh;
  max-height: 45dvh;
  font-family: var(--font-mono);
  line-height: 1.6;
}

.manual-copy-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.manual-copy-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.section-list,
.edit-section-list {
  display: grid;
  min-width: 0;
}

.edit-section-list {
  gap: 14px;
}

.edit-section label {
  display: block;
  margin-bottom: 7px;
  color: var(--text-secondary);
  font-size: 12px;
}

.edit-section :deep(.el-textarea__inner) {
  border: 1px solid var(--glass-border-subtle);
  color: var(--text-primary);
  line-height: 1.7;
  background: var(--glass-bg-subtle);
  box-shadow: none;
}

.ambiguity-note {
  display: flex;
  flex: 0 1 auto;
  flex-direction: column;
  min-height: 0;
  margin-bottom: 12px;
  padding: 10px 12px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--warning) 34%, var(--glass-border-subtle));
  border-left: 4px solid var(--warning);
  border-radius: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  background: color-mix(in srgb, var(--warning) 8%, var(--glass-bg-subtle));
}

.context-warning {
  flex: 0 1 auto;
  margin: 0 0 10px;
  padding: 8px 12px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--warning) 34%, var(--glass-border-subtle));
  border-left: 3px solid var(--warning);
  border-radius: 10px;
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--warning) 7%, var(--glass-bg-subtle));
  font-size: 12px;
}

.context-warning.is-open {
  max-height: 28%;
}

.context-warning-toggle {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  width: 100%;
  min-height: 32px;
  align-items: center;
  column-gap: 8px;
  row-gap: 4px;
  padding: 0;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.context-warning-toggle:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--warning) 72%, transparent);
  outline-offset: 2px;
  border-radius: 6px;
}

.context-warning-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.context-warning-title :deep(svg),
.context-warning-chevron {
  width: 15px;
  height: 15px;
  flex: none;
  color: var(--warning);
}

.context-warning-chevron {
  transition: transform var(--duration-ui) var(--ease-standard);
}

.context-warning-chevron.is-open {
  transform: rotate(180deg);
}

.context-warning-title strong {
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-warning-title span {
  flex: none;
  color: var(--warning);
  font-family: var(--font-mono);
  font-size: 12px;
}

.context-warning-preview {
  grid-column: 1 / -1;
  overflow: hidden;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-warning ul {
  max-height: 120px;
  margin: 8px 0 0;
  padding-left: 18px;
  overflow-y: auto;
  line-height: 1.5;
}

.context-warning li + li {
  margin-top: 4px;
}

.ambiguity-note.is-open {
  max-height: 42%;
}

.ambiguity-toggle {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  width: 100%;
  min-height: 44px;
  align-items: center;
  column-gap: 8px;
  row-gap: 6px;
  padding: 0;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
  touch-action: manipulation;
}

.ambiguity-toggle:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--warning) 72%, transparent);
  outline-offset: 2px;
  border-radius: 6px;
}

.ambiguity-title,
.ambiguity-meta {
  display: flex;
  align-items: center;
}

.ambiguity-title {
  min-width: 0;
  gap: 7px;
}

.ambiguity-meta {
  gap: 6px;
}

.ambiguity-title svg,
.ambiguity-chevron {
  width: 15px;
  flex: none;
  color: var(--warning);
}

.ambiguity-chevron {
  transition: transform 0.18s ease;
}

.ambiguity-chevron.is-open {
  transform: rotate(180deg);
}

.ambiguity-title strong {
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ambiguity-title span,
.ambiguity-meta small {
  flex: none;
  color: var(--warning);
  font-family: var(--font-mono);
  font-size: 12px;
}

.ambiguity-preview {
  grid-column: 1 / -1;
  grid-row: 2;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ambiguity-body {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.ambiguity-note.is-open .ambiguity-body {
  flex: 1 1 auto;
}

.ambiguity-summary {
  margin: 8px 0 0;
  line-height: 1.6;
}

.ambiguity-list {
  display: grid;
  gap: 6px;
  margin: 9px 0 0;
  padding: 9px 2px 2px 17px;
  border-top: 1px solid color-mix(in srgb, var(--warning) 22%, var(--glass-border-subtle));
  line-height: 1.6;
}

@media (max-width: 900px) {
  .result-panel {
    min-height: 0;
    padding: 18px 16px 16px;
  }

  .re-enhance-button {
    margin-bottom: 10px;
  }

  .copy-control :deep(.el-button),
  .open-platform-link,
  .share-prompt-button,
  .manual-copy-actions :deep(.el-button) {
    min-height: 44px;
  }

  .copy-platform-trigger {
    min-width: 44px;
  }

  .ambiguity-note.is-open {
    max-height: min(40vh, 42%);
  }

  .ambiguity-list {
    gap: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ambiguity-chevron {
    transition: none;
  }
}

@media (max-width: 600px) {
  .result-panel {
    padding: 16px 14px 14px;
  }

  .result-heading {
    display: grid;
  }

  .result-actions {
    justify-content: flex-start;
  }

  .signal-source {
    grid-template-columns: repeat(3, auto);
  }

  .signal-flow {
    flex-wrap: wrap;
    justify-content: center;
  }
}
</style>
