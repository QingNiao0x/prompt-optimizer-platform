<script setup lang="ts">
import { ElCollapse, ElCollapseItem, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import { polishDirectedLabel, polishEditingLabel, type PolishExecutionMetadata } from '@/types/textPolish';

// 工作台与历史详情共享同一展示；缺失值保留未知，不将上游默认推测为有效参数。
defineProps<{ metadata?: PolishExecutionMetadata | null }>();
const reasons: Record<string, string> = {
  SOURCE_LANGUAGE_EVIDENCE: '根据正文语言匹配',
  INSUFFICIENT_OR_UNSUPPORTED_LANGUAGE: '语言证据不足或暂无专用资源，使用通用编辑',
};
</script>

<template>
  <ElCollapse class="execution-details">
    <ElCollapseItem title="本次执行信息" name="execution">
      <p v-if="!metadata">执行信息未知</p>
      <template v-else>
        <ElDescriptions :column="1" size="small" border>
          <ElDescriptionsItem label="策略版本">{{ metadata.strategyVersion }}</ElDescriptionsItem>
          <ElDescriptionsItem label="编辑匹配">{{ polishEditingLabel(metadata) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="选择器版本">{{ metadata.selection?.selectorVersion ?? '未知' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="编辑配置">{{ metadata.selection?.profile === 'GENERAL' ? '结合全文判断用途' : metadata.selection?.profile ?? '未知' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="选择依据">{{ metadata.selection ? reasons[metadata.selection.reason] ?? metadata.selection.reason : '未知' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="Skill版本">{{ metadata.skillVersion ?? '未知或未加载' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="基础指令哈希">{{ metadata.resourceHash ?? '未知' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="修复指令哈希">{{ metadata.repairInstructionHash ?? '未记录' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="模型路由">{{ metadata.routeKey }} / {{ metadata.upstreamModel }}</ElDescriptionsItem>
          <ElDescriptionsItem label="调用尝试">{{ metadata.attemptCount }}</ElDescriptionsItem>
          <template v-if="metadata.directedRevision">
            <ElDescriptionsItem label="首段修订">{{ polishDirectedLabel(metadata.directedRevision) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="首段修订版本">{{ metadata.directedRevision.strategyVersion }}</ElDescriptionsItem>
            <ElDescriptionsItem label="首段指令哈希">{{ metadata.directedRevision.instructionHash }}</ElDescriptionsItem>
          </template>
          <ElDescriptionsItem label="实际发送参数"><pre>{{ JSON.stringify(metadata.parameters, null, 2) ?? '未知' }}</pre></ElDescriptionsItem>
        </ElDescriptions>
        <p v-if="metadata.resources == null">资源清单未知</p>
        <p v-else-if="!metadata.resources.length">本次未加载Skill资源</p>
        <ul v-else><li v-for="resource in metadata.resources" :key="resource.id">{{ resource.id }} · {{ resource.version }}<br />{{ resource.sha256 }}</li></ul>
      </template>
    </ElCollapseItem>
  </ElCollapse>
</template>

<style scoped>
.execution-details { margin-top: 16px; overflow-wrap: anywhere; }
.execution-details pre { margin: 0; white-space: pre-wrap; font: inherit; }
.execution-details ul { padding-left: 20px; }
.execution-details li { margin: 8px 0; color: var(--ink-muted); }
.execution-details :deep(.el-descriptions__table) { table-layout: fixed; }
.execution-details :deep(.el-descriptions__label) { width: 100px; }
</style>
