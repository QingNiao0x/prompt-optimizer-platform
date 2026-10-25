<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { ElAlert, ElButton, ElCollapse, ElCollapseItem, ElDialog, ElEmpty, ElInput, ElMessage,
  ElMessageBox, ElOption, ElPagination, ElRadio, ElRadioGroup, ElSelect, ElSwitch, ElTag } from 'element-plus';
import { useTextPolishStore } from '@/stores/textPolish';
import { useAuthStore } from '@/stores/auth';
import { polishStyles } from '@/types/textPolish';
import { comparePolish } from '@/features/textpolish/diff';
import { formatModelVersion } from '@/features/models/modelVersion';

const store = useTextPolishStore(), auth = useAuthStore(), route = useRoute();
const { sourceText, style, depth, audience, requirements, lockedText, modelId, capabilities, selectedModel,
  result, pendingResult, previousResult, editDraft, isEditing, busy, saving, loading, error, dirty, currentText,
  canGenerate, recoveryPending, history, historyLoading } = storeToRefs(store);
const pane = ref('source'), showDiff = ref(false), historyVisible = ref(false), manualCopy = ref(false);
const diff = computed(() => result.value ? comparePolish(result.value.input.sourceText, currentText.value) : []);
const styleLabel = (value: string): string => polishStyles.find(s => s.value === value)?.label ?? value;
watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), () => {
  historyVisible.value = false; manualCopy.value = false; showDiff.value = false; pane.value = 'source';
}, { flush: 'sync' });
const protectDraft = async (): Promise<boolean> => {
  if (!dirty.value) return true;
  try { await ElMessageBox.confirm('当前编辑稿尚未保存，是否放弃编辑稿？', '保留编辑稿',
    { confirmButtonText: '放弃编辑稿', cancelButtonText: '继续编辑', type: 'warning' }); return true; } catch { return false; }
};
onBeforeRouteLeave(async () => protectDraft());
const generate = async (current = false): Promise<void> => { await store.generate(current); if (result.value && !pendingResult.value) pane.value = 'result'; };
const newArticle = async (): Promise<void> => { if (await protectDraft()) { store.reset(); pane.value = 'source'; } };
const acceptPending = async (): Promise<void> => {
  if (pendingResult.value && await protectDraft()) { store.install(pendingResult.value); pane.value = 'result'; }
};
const openHistory = (): void => { historyVisible.value = true; void store.loadHistory(); };
const restore = async (id: string): Promise<void> => {
  if (!busy.value && await protectDraft() && await store.restore(id)) { historyVisible.value = false; pane.value = 'result'; }
};
const remove = async (id: string): Promise<void> => {
  try { await ElMessageBox.confirm('删除后将无法从历史、编辑或继续润色入口访问这条记录。', '删除文章记录',
    { confirmButtonText: '删除记录', cancelButtonText: '取消', type: 'warning' }); } catch { return; }
  await store.remove(id);
};
/** 剪贴板只取可见正文；编辑中明确提示复制的是尚未保存的稿件。 */
const copy = async (): Promise<void> => {
  try { await navigator.clipboard.writeText(currentText.value); ElMessage.success(dirty.value ? '已复制当前编辑稿（尚未保存）。' : '润色正文已复制。'); }
  catch { manualCopy.value = true; }
};
onMounted(async () => {
  await store.load();
  if (route.query.record && typeof route.query.record === 'string') await restore(route.query.record);
  if (route.query.history === '1') openHistory();
});
</script>

<template>
  <section class="polish-workbench">
    <header class="polish-heading">
      <div><h1>文章祛AI味</h1><p>保留原意与细节，让文章更清楚、更自然。</p></div>
      <div class="heading-actions"><ElButton @click="openHistory">文章历史</ElButton><ElButton :disabled="busy || saving" @click="newArticle">新建文章</ElButton></div>
    </header>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <ElAlert v-if="selectedModel?.mock" title="当前为演示模型，只回传原文，不代表真实润色效果。" type="info" :closable="false" />
    <ElAlert v-if="capabilities && !capabilities.persistentHistory" title="演示历史保存在服务内存中，重启后不保留。" type="info" :closable="false" />
    <div v-if="recoveryPending" class="notice-row"><span>该次处理结果尚未确认，原文和上一稿已保留。</span><ElButton :loading="busy" @click="store.recover">查询处理结果</ElButton></div>
    <div v-if="pendingResult" class="notice-row"><span>新结果已生成。你在等待期间修改了内容，当前稿已保留。</span><ElButton @click="acceptPending">查看新结果</ElButton></div>
    <nav class="mobile-panes" aria-label="文章工作台分区">
      <ElButton v-for="item in [{ id: 'source', label: '原文' }, { id: 'settings', label: '设置' }, { id: 'result', label: '结果' }]"
        :key="item.id" :type="pane === item.id ? 'primary' : 'default'" :aria-pressed="pane === item.id" @click="pane = item.id">{{ item.label }}</ElButton>
    </nav>
    <div class="workbench-grid polish-grid" :data-pane="pane">
      <section class="glass-panel polish-column" data-column="source" aria-labelledby="polish-source-title">
        <div class="column-heading"><h2 id="polish-source-title">原始文本</h2><ElTag effect="plain">{{ sourceText.length }} 字符</ElTag></div>
        <p class="hint">粘贴已有文章、邮件或说明。事实、条件和引用会随原文保留。</p>
        <ElInput v-model="sourceText" type="textarea" :rows="20"
          resize="vertical" aria-label="原始文章" placeholder="在这里粘贴需要润色的正文…" />
        <ElAlert v-if="sourceText.length > (capabilities?.maxInputCharacters ?? 12000)" :title="`原文超过 ${capabilities?.maxInputCharacters ?? 12000} 字符，请精简后再润色。已粘贴的内容完整保留。`" type="warning" :closable="false" />
        <label class="field"><span>保持原样的文字（选填）</span><ElInput v-model="lockedText" type="textarea" :rows="3" aria-label="保护原句"
          placeholder="每行粘贴一段原文，例如引用、术语或必须保留的句子。" /></label>
        <p class="hint">最多 20 段，每段不超过 1000 字符。仅在点击润色时提交给所选模型；文章不写入浏览器长期存储，处理状态暂存 24 小时。</p>
      </section>
      <section class="glass-panel polish-column" data-column="settings" aria-labelledby="polish-settings-title">
        <div class="column-heading"><h2 id="polish-settings-title">润色设置</h2></div>
        <p class="hint">选择适合文章用途的文体。</p>
        <ElRadioGroup v-model="style" class="style-options" aria-label="文章文体">
          <ElRadio v-for="item in polishStyles" :key="item.value" :value="item.value" border class="style-card">
            <strong>{{ item.label }}<small v-if="item.value === 'NATURAL'">默认</small></strong><span>{{ item.description }}</span>
          </ElRadio>
        </ElRadioGroup>
        <label class="field"><span>润色模型</span><ElSelect v-model="modelId" aria-label="文章润色模型" :loading="loading" placeholder="选择润色模型">
          <ElOption v-for="model in capabilities?.models ?? []" :key="model.id" :value="model.id" :label="formatModelVersion(model.displayName) + (model.defaultModel ? '（默认）' : '')" />
        </ElSelect></label>
        <ElAlert v-if="capabilities && !capabilities.models.length" title="暂无可用润色模型，请由管理员在模型管理中配置。" type="warning" :closable="false" />
        <ElButton text type="primary" :loading="loading" @click="store.load">刷新模型列表</ElButton>
        <ElCollapse class="advanced"><ElCollapseItem title="高级设置" name="advanced">
          <label class="field"><span>改写幅度</span><ElSelect v-model="depth" aria-label="改写幅度"><ElOption label="轻度润色" value="LIGHT" /><ElOption label="允许调整句式和段落" value="RESTRUCTURE" /></ElSelect></label>
          <label class="field"><span>读者（选填）</span><ElInput v-model="audience" :maxlength="300" aria-label="文章读者" placeholder="例如：团队同事、普通读者" /></label>
          <label class="field"><span>补充要求（选填）</span><ElInput v-model="requirements" type="textarea" :rows="3" :maxlength="1500" aria-label="润色补充要求" placeholder="例如：保留标题和段落，篇幅尽量接近原文。" /></label>
        </ElCollapseItem></ElCollapse>
        <ElButton type="primary" class="primary-action" :loading="busy" :disabled="!canGenerate || !!pendingResult" @click="generate()">{{ result ? '基于原文重新生成' : '开始润色' }}</ElButton>
        <p v-if="dirty" class="hint">当前编辑稿尚未保存，请先保存或取消编辑。</p>
        <p class="hint">切换文体只更新设置，点击按钮才会生成。文体不代表质量等级或 AI 检测分数。</p>
      </section>
      <section class="glass-panel polish-column" data-column="result" aria-labelledby="polish-result-title" :aria-busy="busy">
        <div class="column-heading"><h2 id="polish-result-title">润色结果</h2><ElTag v-if="dirty" type="warning">编辑稿未保存</ElTag></div>
        <ElEmpty v-if="!result" :description="busy ? '正在润色文章，原文会保持在左侧。' : '输入文章并选择文体，获取完整润色正文。'" />
        <template v-else>
          <p class="hint">{{ styleLabel(result.input.style) }} · {{ formatModelVersion(result.modelName) }} · {{ result.sourceMode === 'CURRENT' ? '基于已保存的当前稿' : '基于原文' }}</p>
          <ElAlert v-if="!result.historySaved" title="正文已生成，历史尚未保存。请重试保存或复制正文。" type="warning" :closable="false" />
          <ElButton v-if="!result.historySaved" :loading="saving" @click="store.save">重试保存历史</ElButton>
          <div class="compare-toggle"><span>与本次原文对照</span><ElSwitch v-model="showDiff" aria-label="显示文章差异" /></div>
          <ElInput v-if="isEditing" v-model="editDraft" type="textarea" :rows="20" :maxlength="capabilities?.maxOutputCharacters ?? 24000" show-word-limit aria-label="润色编辑稿" />
          <div v-else-if="showDiff" class="diff-view" aria-label="文章差异"><div v-for="(line, index) in diff" :key="index" :class="['diff-line', line.kind]"><span class="diff-label">{{ line.kind === 'removed' ? '原文' : line.kind === 'added' ? '改为' : '' }}</span><span>{{ line.text || '\u00a0' }}</span></div></div>
          <pre v-else class="article-text" aria-label="润色正文">{{ result.polishedText }}</pre>
          <div class="result-actions">
            <ElButton v-if="!isEditing" :disabled="!result.historySaved" @click="isEditing = true; editDraft = result.polishedText">编辑正文</ElButton>
            <template v-else><ElButton type="primary" :loading="saving" :disabled="busy || !editDraft.trim()" @click="store.save">保存编辑</ElButton><ElButton :disabled="saving" @click="isEditing = false; editDraft = result.polishedText">取消编辑</ElButton></template>
            <ElButton type="primary" plain :disabled="!currentText.trim()" @click="copy">{{ isEditing ? '复制当前稿' : '复制正文' }}</ElButton>
            <ElButton :disabled="!canGenerate || isEditing || !result.historySaved || !!pendingResult" @click="generate(true)">以当前稿继续润色</ElButton>
            <ElButton v-if="previousResult" :disabled="dirty || busy || saving" @click="store.undo">返回上一稿</ElButton>
          </div>
          <p v-if="isEditing" class="hint">差异和检查提示以已保存正文为准，保存后更新。复制当前稿不会自动保存。</p>
          <div v-if="result.checks.length" class="check-list"><p v-for="check in result.checks" :key="check.code"><strong>{{ check.message }}</strong><span v-if="check.before">原文：{{ check.before }}<br />当前：{{ check.after }}</span></p></div>
          <p class="hint">请结合原文核对对象、数值、条件和引用。差异提示不代表完整语义检查。</p>
        </template>
      </section>
    </div>
    <ElDialog v-model="manualCopy" title="手动复制文章" width="min(760px, calc(100vw - 24px))"><p>浏览器未允许写入剪贴板，请选中下方正文手动复制。</p><ElInput :model-value="currentText" type="textarea" :rows="16" readonly aria-label="手动复制文章" /></ElDialog>
    <ElDialog v-model="historyVisible" title="文章润色历史" width="min(900px, calc(100vw - 24px))">
      <ElButton :loading="historyLoading" @click="store.loadHistory(history.current)">刷新记录</ElButton>
      <ElEmpty v-if="!historyLoading && !history.records.length" description="还没有文章润色记录。" />
      <article v-for="document in history.records" :key="document.id" class="history-row"><div><strong>{{ document.input.sourceText.slice(0, 70) }}</strong><p>{{ styleLabel(document.input.style) }} · {{ formatModelVersion(document.modelName) }} · {{ new Date(document.createdAt).toLocaleString('zh-CN') }}</p><p>{{ document.polishedText.slice(0, 100) }}</p></div><div class="heading-actions"><ElButton :disabled="busy || saving" @click="restore(document.id)">打开文章</ElButton><ElButton type="danger" text :disabled="busy || saving" @click="remove(document.id)">删除</ElButton></div></article>
      <ElPagination v-if="history.total" layout="prev, pager, next, total" :current-page="history.current" :page-size="10" :total="history.total" @current-change="store.loadHistory" />
    </ElDialog>
  </section>
</template>

<style scoped>
.polish-workbench { display: grid; gap: 18px; padding: 24px; }
.polish-heading, .heading-actions, .column-heading, .result-actions, .notice-row, .compare-toggle { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.polish-heading h1 { margin: 0; font-size: 24px; color: var(--ink-strong); } .polish-heading p { margin: 6px 0 0; color: var(--ink-muted); font-size: 13px; }
.polish-grid { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(240px, .8fr) minmax(300px, 1.2fr); align-items: start; gap: 16px; }
.polish-column { min-width: 0; padding: 18px; border-radius: var(--radius-md); } .column-heading { margin-bottom: 12px; flex-wrap: wrap; } .column-heading h2 { margin: 0; font-size: 16px; color: var(--ink-strong); }
.hint { color: var(--ink-muted); font-size: 12px; line-height: 1.7; } .field { display: grid; gap: 7px; margin-top: 16px; } .field > span { color: var(--ink-strong); font-size: 13px; }
.style-options { display: grid; gap: 10px; width: 100%; } .style-card { display: flex; width: 100%; height: auto; margin: 0; padding: 14px 12px; border-radius: var(--radius-sm); white-space: normal; }
.style-card :deep(.el-radio__label) { white-space: normal; display: grid; gap: 5px; line-height: 1.6; min-width: 0; } .style-card strong { color: var(--ink-strong); font-size: 14px; } .style-card span { color: var(--ink-muted); font-size: 12px; } .style-card small { margin-left: 8px; color: var(--accent); font-weight: 400; }
.advanced { margin-top: 12px; } .primary-action { width: 100%; margin-top: 18px; } .compare-toggle { justify-content: flex-start; margin: 14px 0; font-size: 13px; color: var(--ink-muted); }
.article-text, .diff-view { min-height: 260px; max-height: 700px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.9; color: var(--ink-strong); margin: 0; }
.diff-line { display: flex; gap: 8px; } .diff-line.removed { background: var(--orange-soft); } .diff-line.added { background: var(--green-soft); } .diff-label { flex: 0 0 26px; color: var(--ink-muted); font-size: 11px; user-select: none; }
.result-actions { justify-content: flex-start; flex-wrap: wrap; margin-top: 14px; } .result-actions :deep(.el-button + .el-button) { margin-left: 0; }
.check-list { margin-top: 20px; border-top: 1px solid var(--line-subtle); font-size: 12px; color: var(--ink-muted); } .check-list strong { font-weight: 400; } .check-list span { display: block; overflow-wrap: anywhere; }
.notice-row { flex-wrap: wrap; font-size: 13px; color: var(--ink-muted); } .history-row { display: flex; justify-content: space-between; gap: 16px; padding: 16px 0; border-bottom: 1px solid var(--line-subtle); } .history-row > div { min-width: 0; } .history-row p { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; color: var(--ink-muted); } .history-row strong { overflow-wrap: anywhere; } .mobile-panes { display: none; }
@media (max-width: 1100px) and (min-width: 901px) { .polish-workbench { padding: 16px; } .polish-grid { grid-template-columns: minmax(210px, 1fr) minmax(210px, .8fr) minmax(250px, 1.1fr); gap: 10px; } .polish-column { padding: 12px; } }
@media (max-width: 900px) { .polish-grid { grid-template-columns: minmax(0, 1fr); } .mobile-panes { display: flex; gap: 8px; } .mobile-panes .el-button { flex: 1; margin-left: 0; } .polish-grid[data-pane='source'] [data-column]:not([data-column='source']), .polish-grid[data-pane='settings'] [data-column]:not([data-column='settings']), .polish-grid[data-pane='result'] [data-column]:not([data-column='result']) { display: none; } }
@media (max-width: 640px) { .polish-workbench { padding: 12px; } .polish-heading { align-items: flex-start; flex-direction: column; } .polish-column { padding: 14px; } .history-row { flex-direction: column; } }
</style>
