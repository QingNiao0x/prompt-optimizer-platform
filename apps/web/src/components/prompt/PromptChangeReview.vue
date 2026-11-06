<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElCollapse, ElCollapseItem } from 'element-plus';
import { comparePromptText } from '@/features/optimization/promptDiff';
import type { OptimizationResult } from '@/types/api';

const props = defineProps<{ previous: OptimizationResult; current: OptimizationResult }>();
const opened = ref<string[]>([]);
// 只在用户展开时比较，旧长文本记录仍可在相同工作台查看。
const changes = computed(() => opened.value.includes('changes')
  ? comparePromptText(props.previous.optimizedPrompt, props.current.optimizedPrompt) : []);
const unchanged = computed(() => props.previous.optimizedPrompt === props.current.optimizedPrompt);
const versionLabel = (result: OptimizationResult): string =>
  `${result.recordId ? result.recordId.slice(0, 8) : '当前会话'} · 编辑版本 ${result.editRevision ?? 0}`;
watch(() => props.current, () => { opened.value = []; });
</script>

<template>
  <ElCollapse v-model="opened" class="prompt-change-review" data-testid="prompt-change-review">
    <ElCollapseItem name="changes" title="查看与上一稿的差异">
      <p class="version">上一稿 {{ versionLabel(previous) }}<br />当前稿 {{ versionLabel(current) }}</p>
      <p v-if="unchanged" role="status">当前正文与上一稿一致。</p>
      <template v-else>
        <p class="legend"><span class="added">新增内容</span><del class="removed">删除内容</del></p>
        <p class="hint">可核对原要求与 Plan 回答是否仍被保留。</p>
      </template>
      <div class="change-content" tabindex="0" aria-label="修改前后文字差异">
        <template v-for="(change, index) in changes" :key="index">
          <ins v-if="change.kind === 'added'" class="added">{{ change.text }}</ins>
          <del v-else-if="change.kind === 'removed'" class="removed">{{ change.text }}</del>
          <span v-else>{{ change.text }}</span>
        </template>
      </div>
    </ElCollapseItem>
  </ElCollapse>
</template>

<style scoped>
.prompt-change-review { margin: 10px 0; }
.version, .hint, .legend { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.legend { display: flex; gap: 12px; }
.change-content { max-height: 340px; overflow: auto; padding: 12px; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); background: var(--surface-code); color: var(--ink-strong); white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.added { background: var(--green-soft); color: var(--success); text-decoration: none; }
.removed { background: var(--orange-soft); color: var(--danger); }
</style>
