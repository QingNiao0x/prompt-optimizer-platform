<script setup lang="ts">
import { ElScrollbar, ElTag } from 'element-plus';
import { computed } from 'vue';

import type { ContextTransmissionReport, TransmissionStatus } from '@/features/context-analysis/contextTransmission';

const props = defineProps<{ report: ContextTransmissionReport }>();
const enhancement = computed(() => props.report.phase === 'ENHANCEMENT' && !props.report.mock);
const heading = computed(() => props.report.phase === 'ENHANCEMENT' ? '本次增强资料范围'
  : props.report.phase === 'PLAN_CONTEXT' ? 'Plan 准备资料范围' : '上下文分析资料范围');
const explanation = computed(() => props.report.phase === 'PLAN_CONTEXT'
  ? '这里显示后端选取的正文；Plan 使用裁剪后的摘要，最终增强会另行选取资料。'
  : props.report.phase === 'ANALYSIS' ? '这里显示本次后端分析范围，尚未发送给增强模型。'
  : props.report.mock ? '本次为模拟增强，未调用外部模型；这里显示纳入增强输入的正文。'
  : '以本次增强返回的上下文为依据，显示纳入模型输入的正文。发送不代表模型已理解或采用。');
const statusLabel = (status: TransmissionStatus): string => ({
  FULL: enhancement.value ? '完整发送' : '完整选取',
  PARTIAL: enhancement.value ? '部分发送' : '部分选取',
  NONE: enhancement.value ? '未发送正文' : '未选取正文',
  PENDING: '等待确认', UNKNOWN: '状态未知',
})[status];
const statusType = (status: TransmissionStatus): 'success' | 'warning' | 'info' =>
  status === 'FULL' ? 'success' : status === 'PARTIAL' || status === 'UNKNOWN' ? 'warning' : 'info';
</script>

<template>
  <section class="transmission-report" aria-label="本次资料发送范围" aria-live="polite">
    <div class="transmission-heading">
      <strong>{{ heading }}</strong>
      <ElTag size="small" effect="plain" round :type="report.status === 'FAILED' ? 'warning' : 'info'">
        {{ report.status === 'COMPLETE' ? '已核对' : report.status === 'FAILED' ? '请求未成功' : '请求中' }}
      </ElTag>
    </div>
    <p>{{ explanation }}</p>
    <small>完整范围指本次已读取或提取的正文。本地项目索引不会全部上传。</small>
    <ElScrollbar v-if="report.entries.length" max-height="280px">
      <ul class="transmission-list">
        <li v-for="entry in report.entries" :key="entry.path" :data-path="entry.path">
          <div class="transmission-file">
            <span :title="entry.path">{{ entry.path }}</span>
            <ElTag size="small" effect="plain" round :type="statusType(entry.contentStatus)">
              {{ statusLabel(entry.contentStatus) }}
            </ElTag>
          </div>
          <small>{{ entry.message }}</small>
          <details v-if="entry.preview">
            <summary>查看本次安全片段（{{ entry.selectedCharacters.toLocaleString('zh-CN') }} 字符）</summary>
            <pre>{{ entry.preview }}</pre>
          </details>
        </li>
      </ul>
    </ElScrollbar>
    <p v-else>本次未选择文件资料。</p>
  </section>
</template>

<style scoped>
.transmission-report {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 12px;
  color: var(--text-secondary);
  background: var(--glass-bg-subtle);
  font-size: 12px;
  line-height: 1.6;
}
.transmission-heading,
.transmission-file {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.transmission-heading strong { color: var(--text-primary); }
.transmission-file span:first-child { min-width: 0; overflow-wrap: anywhere; }
.transmission-file :deep(.el-tag) { flex-shrink: 0; }
.transmission-report p { margin: 8px 0; }
.transmission-report small { display: block; color: var(--text-tertiary); line-height: 1.6; }
.transmission-list { display: grid; gap: 10px; margin: 12px 0 0; padding: 0; list-style: none; }
.transmission-list li { padding-top: 8px; border-top: 1px solid var(--glass-border-subtle); }
.transmission-list summary { margin-top: 4px; cursor: pointer; color: var(--text-secondary); }
.transmission-list pre {
  max-height: 180px;
  margin: 8px 0 0;
  overflow: auto;
  color: var(--text-primary);
  font: inherit;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
