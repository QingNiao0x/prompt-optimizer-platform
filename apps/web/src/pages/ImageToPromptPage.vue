<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import {
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElEmpty, ElInput, ElMessage,
  ElMessageBox, ElOption, ElPagination, ElRadio, ElRadioGroup, ElSelect, ElSwitch, ElTag, ElUpload,
  type UploadFile,
} from 'element-plus';
import ImagePromptHelp from '@/components/imageprompt/ImagePromptHelp.vue';
import { useImagePromptStore } from '@/stores/imagePrompt';
import { useAuthStore } from '@/stores/auth';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getApiErrorMessage } from '@/services/http';
import type { ImagePromptDocument, ImagePromptHistoryPage, ImageReferenceRole } from '@/types/imagePrompt';
import { formatModelVersion } from '@/features/models/modelVersion';

const store = useImagePromptStore();
const route = useRoute();
const { mode, modelId, creationIntent, exactText, context, language, targetTool, files, capabilities, availableModels,
  selectedModel, modelInputError, plan, answers, result, editDraft, isEditing, phase, error, cleanupWarning, recoveryId,
  recoveryPending, consent, busy, hasDraftChanges, canStart, startDisabledReason, undoDocument } = storeToRefs(store);
const creation = computed(() => mode.value === 'CUSTOM_CREATION');
const rewriteInstruction = ref('');
const historyVisible = ref(false);
const historyPage = ref<ImagePromptHistoryPage>({ records: [], total: 0, size: 10, current: 1, pages: 0 });
const historyLoading = ref(false);
const historyError = ref('');
const manualCopyVisible = ref(false);
const pane = ref('images');
const phaseLabel = computed(() => ({ IDLE: '', UPLOADING: '上传并校验图片中', PLANNING: '梳理创作意图中',
  GENERATING: '生成提示词中', SAVING: '保存正文中', RECOVERING: '查询处理结果中' }[phase.value]));
const analysis = computed(() => plan.value?.analysis ?? result.value?.analysis ?? []);
let active = true;
let historyEpoch = 0;
let historyLoad = 0;
const auth = useAuthStore();
// 页面私有历史和迟到响应也必须随身份隔离；只清 Pinia 不能撤回已发出的请求。
watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), () => {
  historyEpoch += 1; historyLoad += 1; historyVisible.value = false; historyLoading.value = false;
  historyPage.value = { records: [], total: 0, size: 10, current: 1, pages: 0 };
  historyError.value = ''; rewriteInstruction.value = ''; manualCopyVisible.value = false;
}, { flush: 'sync' });

/** 切换只变更业务输入；旧结果和编辑稿保留，不自动上传或调用模型。 */
const togglePlan = (value: string | number | boolean): void => { mode.value = value ? 'CUSTOM_CREATION' : 'REFERENCE_RECONSTRUCTION'; };
const selectFile = (file: UploadFile): void => {
  if (!file.raw) return;
  try { store.addFiles([file.raw]); } catch (failure: unknown) { ElMessage.warning(getApiErrorMessage(failure)); }
};
const start = async (): Promise<void> => { await store.start(); if (active) pane.value = plan.value ? 'settings' : result.value ? 'result' : pane.value; };
const confirm = async (): Promise<void> => { await store.confirm(); if (active && result.value && !plan.value) pane.value = 'result'; };
const loadHistory = async (current = 1): Promise<void> => {
  const epoch = historyEpoch, load = ++historyLoad;
  historyLoading.value = true; historyError.value = '';
  try { const value = await imagePromptApi.history(current, 10); if (active && epoch === historyEpoch && load === historyLoad) historyPage.value = value; }
  catch (failure: unknown) { if (active && epoch === historyEpoch && load === historyLoad) historyError.value = getApiErrorMessage(failure); }
  finally { if (active && epoch === historyEpoch && load === historyLoad) historyLoading.value = false; }
};
const openHistory = (): void => { historyVisible.value = true; void loadHistory(); };
const restoreHistory = async (document: ImagePromptDocument): Promise<void> => {
  const epoch = historyEpoch;
  if (busy.value) return;
  if (hasDraftChanges.value) {
    try { await ElMessageBox.confirm('当前编辑稿尚未保存。确定放弃编辑并打开这条历史记录吗？', '打开历史记录',
      { confirmButtonText: '放弃编辑并打开', cancelButtonText: '保留编辑稿', type: 'warning' }); } catch { return; }
  }
  try {
    if (!active || epoch !== historyEpoch) return;
    const value = await imagePromptApi.detail(document.recordId!);
    if (active && epoch === historyEpoch) { store.restore(value); historyVisible.value = false; pane.value = 'result'; }
  } catch (failure: unknown) { if (active && epoch === historyEpoch) historyError.value = getApiErrorMessage(failure); }
};
const deleteHistory = async (document: ImagePromptDocument): Promise<void> => {
  const epoch = historyEpoch;
  try { await ElMessageBox.confirm('删除后不能再查看、编辑或再次优化这条记录。', '删除图像提示词记录',
    { confirmButtonText: '删除记录', cancelButtonText: '取消', type: 'warning' }); } catch { return; }
  try {
    if (!active || epoch !== historyEpoch) return;
    await imagePromptApi.remove(document.recordId!);
    if (!active || epoch !== historyEpoch) return;
    if (result.value?.recordId === document.recordId) { result.value = undefined; isEditing.value = false; editDraft.value = ''; undoDocument.value = undefined; }
    await loadHistory(historyPage.value.current);
  } catch (failure: unknown) { if (active && epoch === historyEpoch) historyError.value = getApiErrorMessage(failure); }
};
const copy = async (): Promise<void> => {
  if (!result.value || isEditing.value) return;
  try { await navigator.clipboard.writeText(result.value.optimizedPrompt); ElMessage.success('提示词已复制。'); }
  catch { manualCopyVisible.value = true; }
};
const cancelEdit = (): void => { editDraft.value = result.value?.optimizedPrompt ?? ''; isEditing.value = false; };
const clearTask = async (): Promise<void> => {
  try { await ElMessageBox.confirm('清空当前图片、构想和编辑草稿？已保存历史不受影响。', '新建图像任务',
    { confirmButtonText: '清空当前任务', cancelButtonText: '保留当前任务' }); } catch { return; }
  store.reset(); pane.value = 'images';
};
onBeforeRouteLeave(async () => {
  if (!busy.value && !hasDraftChanges.value) return true;
  try { await ElMessageBox.confirm('离开会停止当前页面等待，已提交的模型请求可能继续处理；本次草稿将保留在当前浏览器内存中。', '离开图像工作台',
    { confirmButtonText: '保留草稿并离开', cancelButtonText: '继续当前任务' }); return true; } catch { return false; }
});
onMounted(() => { void store.refreshModels(); if (route.query.history === '1') openHistory(); });
onBeforeUnmount(() => { active = false; store.cancelPending(); });
</script>

<template>
  <section class="image-workbench">
    <header class="image-heading">
      <div><h1>图像转提示词</h1><p>关闭 Plan，从图到文还原参考图；开启 Plan，从构想出发创作新图。</p></div>
      <div class="heading-actions"><ElButton @click="openHistory">图像历史</ElButton><ElButton :disabled="busy" @click="clearTask">新建任务</ElButton></div>
    </header>
    <ElAlert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <ElAlert v-if="cleanupWarning" type="warning" :title="cleanupWarning" :closable="false" />
    <ElAlert v-if="selectedModel?.mock" type="warning" title="当前使用演示模型，不具备真实视觉识别能力。选择已登记视觉能力的真实模型后可进行识图。" :closable="false" show-icon />
    <ElAlert v-if="capabilities && !capabilities.persistentHistory" type="info" title="本机演示历史保存在服务内存中，服务重启后不会保留。" :closable="false" />
    <div v-if="recoveryId && recoveryPending" class="recovery-actions"><span>该次请求的结果尚未确认。</span><ElButton :loading="busy" @click="store.recover">查询处理结果</ElButton></div>
    <nav class="mobile-panes" aria-label="图像工作台分区">
      <ElButton v-for="item in [{ id: 'images', label: '图片' }, { id: 'settings', label: '设置' }, { id: 'result', label: '结果' }]"
        :key="item.id" :type="pane === item.id ? 'primary' : 'default'" :aria-pressed="pane === item.id" @click="pane = item.id">{{ item.label }}</ElButton>
    </nav>
    <div class="workbench-grid image-grid" :data-pane="pane">
      <section class="glass-panel image-column" data-column="images" aria-labelledby="reference-images-title">
        <div class="column-heading"><h2 id="reference-images-title">{{ creation ? '灵感参考（选填）' : '参考图片' }}</h2><ElTag effect="plain">{{ files.length }} / {{ creation ? 3 : 1 }}</ElTag></div>
        <p class="hint">{{ creation ? '也可以只写构想。附图时，请指定借鉴对象、风格还是布局。' : '选择一张参考图，提取实际可见的画面内容，不发起创作问答。' }}</p>
        <ElUpload drag accept="image/jpeg,image/png" :auto-upload="false" :show-file-list="false" :disabled="busy" :on-change="selectFile" aria-label="选择参考图片">
          <div class="upload-copy"><strong>拖入图片，或点击选择</strong><span>JPEG / PNG · 单张不超过 10 MiB</span><small>最多 1500 万像素，单边 8192 像素</small></div>
        </ElUpload>
        <article v-for="file in files" :key="file.key" class="reference-card">
          <img :src="file.previewUrl" :alt="file.name" />
          <div class="reference-meta"><span>{{ file.name }}</span><ElButton text type="danger" :disabled="busy" @click="store.removeFile(file.key)">移除</ElButton></div>
          <ElSelect v-if="creation" :model-value="file.role" :aria-label="`${file.name}的参考用途`" :disabled="busy" @update:model-value="store.changeRole(file.key, $event as ImageReferenceRole)">
            <ElOption label="整体灵感" value="GENERAL" /><ElOption label="只参考主体" value="SUBJECT" /><ElOption label="只参考风格与配色" value="STYLE" /><ElOption label="只参考布局" value="LAYOUT" />
          </ElSelect>
          <p v-if="file.asset" class="hint">{{ file.asset.processing.join('；') }}。临时图片保留约一小时。</p><p v-else class="hint">仅本地预览，尚未上传。</p>
        </article>
        <ElAlert v-if="!creation && files.length > 1" title="直接增强只使用一张图，请移除多余参考图。" type="warning" :closable="false" />
        <ElCheckbox v-if="files.length" v-model="consent" :disabled="busy" class="upload-consent">我已检查图片隐私，同意点击操作后将所选图片发送至平台后端与所选模型服务。</ElCheckbox>
        <p class="hint">原图使用私有临时存储，不进入长期历史；历史中的文字与分析摘要也可能包含图片内容，请自行核对。无需单独 OCR 服务。</p>
        <div class="analysis-section"><h3>{{ plan ? '本次 Plan 的视觉分析' : result ? '已生成结果的视觉分析' : '视觉分析' }}</h3>
          <p v-if="!analysis.length" class="hint">{{ creation && !files.length ? '纯文字创作不进行图片分析。' : '实际分析结果返回后显示，文件预览不代表已识图。' }}</p>
          <article v-for="(item, index) in analysis" :key="`${item.imageId}-${index}`" class="observation">
            <div><ElTag size="small" :type="item.status === 'UNCERTAIN' ? 'warning' : 'info'">{{ item.category }}</ElTag><small>{{ item.status === 'UNCERTAIN' ? '待核对' : item.status === 'INTERPRETATION' ? '风格判断' : '可见观察' }}</small></div>
            <p>{{ item.description }}</p><small>来源：{{ result?.sources.find(s => s.id === item.imageId)?.name ?? files.find(f => f.asset?.id === item.imageId)?.name ?? '参考图片' }}</small>
          </article>
        </div>
      </section>
      <section class="glass-panel image-column" data-column="settings" aria-labelledby="image-creation-title">
        <div class="column-heading"><h2 id="image-creation-title">模式与要求</h2><ElSwitch :model-value="creation" active-text="Plan 开启" inactive-text="关闭" aria-label="Plan 创作模式" :disabled="busy" @change="togglePlan" /></div>
        <div class="mode-descriptions"><p><strong>关闭：从图到文。</strong>还原参考图的可见内容，不引导创作。</p><p><strong>开启：从无到有。</strong>确认你的构想、风格和细节，辅助创作全新图片。参考图可选。</p></div>
        <label class="field"><span>本次模型</span><ElSelect v-model="modelId" :disabled="busy" placeholder="选择本次可用模型" aria-label="图像任务模型"><ElOption v-for="model in availableModels" :key="model.id" :label="`${formatModelVersion(model.displayName)}${model.mock ? '（演示）' : model.vision ? '（视觉）' : '（纯文字）'}`" :value="model.id" /></ElSelect></label>
        <p class="hint">与提示词增强共用模型目录，由模型管理统一配置。带图需开放视觉能力；无图 Plan 可使用文字模型。<ElButton link type="primary" :disabled="busy" :loading="store.isLoadingModels" @click="store.refreshModels">刷新模型</ElButton></p>
        <ElAlert v-if="modelInputError" :title="modelInputError" type="info" :closable="false" />
        <p v-if="!availableModels.length && !store.isLoadingModels" class="hint">当前没有可用模型，请刷新或联系管理员。</p>
        <label v-if="creation" class="field"><span>想创作什么图片？</span><ElInput v-model="creationIntent" type="textarea" :rows="4" maxlength="6000" show-word-limit :disabled="busy" placeholder="例如：为读书会创作一张温暖的招募海报，用柔和插画表现窗边阅读。" aria-label="新图创作构想" /></label>
        <label v-if="creation" class="field"><span>画面必须出现的文字（选填，逐字保留）</span><ElInput v-model="exactText" type="textarea" :rows="2" maxlength="2000" :disabled="busy" placeholder="单独填写标题、卡片文字等，不与风格选择混在一起。" aria-label="画面指定文案" /></label>
        <label v-if="creation" class="field"><span>主动补充项目上下文（选填）</span><ElInput v-model="context" type="textarea" :rows="2" maxlength="6000" :disabled="busy" placeholder="只填写本次需要的品牌规范、产品事实或设计约束；不会自动读取源码。" aria-label="图像项目上下文" /></label>
        <div class="output-fields"><label class="field"><span>提示词语言</span><ElSelect v-model="language" :disabled="busy" aria-label="提示词语言"><ElOption label="中文" value="ZH" /><ElOption label="英文" value="EN" /></ElSelect></label><label class="field"><span>下游绘图工具</span><ElInput v-model="targetTool" maxlength="100" :disabled="busy" aria-label="下游绘图工具" placeholder="通用文生图" /></label></div>
        <ElButton type="primary" class="primary-action" :loading="busy && phase !== 'SAVING'" :disabled="!canStart" @click="start">{{ creation ? '梳理创作意图' : '提取参考图提示词' }}</ElButton>
        <p v-if="phaseLabel" role="status" class="hint">{{ phaseLabel }}</p>
        <p v-if="!busy && startDisabledReason && startDisabledReason !== modelInputError" role="status" class="hint start-disabled-reason">{{ startDisabledReason }}</p>
        <section v-if="plan" class="plan-section" aria-labelledby="image-plan-title"><h3 id="image-plan-title">确认新图创作方向</h3><p>{{ plan.summary }}</p>
          <p v-if="!plan.questions.length" class="hint">创作要求已充分，无需补充问题；确认后即可生成新图提示词。</p>
          <fieldset v-for="q in plan.questions" :key="q.id" :disabled="busy" class="plan-question"><legend>{{ q.question }}{{ q.required ? '（必答）' : '（选答）' }}</legend>
            <ElRadioGroup v-if="q.type === 'SINGLE_CHOICE'" :model-value="answers[q.id]?.optionIds[0] ?? ''" :aria-label="q.question" @update:model-value="answers[q.id].optionIds = [$event as string]"><ElRadio v-for="option in q.options" :key="option.id" :value="option.id">{{ option.label }}</ElRadio></ElRadioGroup>
            <ElCheckboxGroup v-else-if="q.type === 'MULTIPLE_CHOICE'" v-model="answers[q.id].optionIds" :aria-label="q.question"><ElCheckbox v-for="option in q.options" :key="option.id" :value="option.id">{{ option.label }}</ElCheckbox></ElCheckboxGroup>
            <ElInput v-model="answers[q.id].customText" type="textarea" :rows="2" maxlength="2000" :aria-label="`${q.question}的补充回答`" :placeholder="q.type === 'TEXT' ? '填写你的要求，也可以明确委托 AI 设计。' : '可补充说明，与已选选项一起保留。'" />
          </fieldset><ElButton type="primary" :disabled="busy || recoveryPending || hasDraftChanges" @click="confirm">确认并生成新图提示词</ElButton>
        </section>
      </section>
      <section class="glass-panel image-column result-column" data-column="result" aria-labelledby="image-result-title">
        <div class="column-heading"><h2 id="image-result-title">提示词结果</h2><ElTag v-if="result" effect="plain">{{ result.mode === 'CUSTOM_CREATION' ? '新图创作' : '参考图还原' }}</ElTag></div>
        <ElEmpty v-if="!result" description="生成后在这里核对、编辑和复制提示词。" />
        <template v-else>
          <p class="hint">{{ result.modelName }}{{ result.mock ? ' · 演示结果' : '' }} · {{ result.sources.length ? `${result.sources.length} 张图片来源` : '纯文字创作' }}</p>
          <ElAlert v-if="!result.historySaved" title="提示词已生成，历史尚未保存。可以复制当前正文或重试保存，不会重复调用模型。" type="warning" :closable="false" /><ElButton v-if="!result.historySaved" :loading="phase === 'SAVING'" @click="store.retrySave">重试保存历史</ElButton>
          <ElInput v-if="isEditing" v-model="editDraft" type="textarea" :rows="18" maxlength="30000" show-word-limit aria-label="编辑图像提示词" /><pre v-else class="prompt-text">{{ result.optimizedPrompt }}</pre>
          <div class="result-actions"><template v-if="isEditing"><ElButton type="primary" :loading="phase === 'SAVING'" :disabled="busy || !editDraft.trim()" @click="store.saveEdit()">保存正文</ElButton><ElButton :disabled="busy" @click="cancelEdit">取消编辑</ElButton></template><template v-else><ElButton type="primary" @click="copy">复制提示词</ElButton><ElButton :disabled="busy || !result.recordId" @click="isEditing = true; editDraft = result.optimizedPrompt">编辑</ElButton><ElButton :disabled="busy || !undoDocument || !result.recordId" @click="store.undo">撤销上次修改</ElButton></template></div>
          <ul v-if="result.warnings.length" class="result-warnings"><li v-for="warning in result.warnings" :key="warning">{{ warning }}</li></ul>
          <p v-if="result.sources.length" class="hint">历史不保存原图。已有正文可以继续使用；需要重新识图时，请重新选择参考图。</p>
          <label class="field"><span>基于当前正文再次优化</span><ElInput v-model="rewriteInstruction" type="textarea" :rows="2" maxlength="3000" :disabled="busy" :placeholder="result.mode === 'REFERENCE_RECONSTRUCTION' ? '仅整理表达。需要新增内容或改变场景，请开启 Plan。' : '描述希望如何调整当前新图提示词。'" aria-label="图像提示词再次优化要求" /></label>
          <ElButton :disabled="busy || hasDraftChanges || !result.recordId || !rewriteInstruction.trim() || recoveryPending" @click="store.rewrite(rewriteInstruction)">基于已有文字优化</ElButton><p class="hint">此操作使用已保存正文和分析摘要，不会重新读取图片。</p>
        </template>
      </section>
    </div>
    <ImagePromptHelp />
    <ElDialog v-model="manualCopyVisible" title="手动复制提示词" width="min(680px, calc(100vw - 24px))"><p>浏览器未允许写入剪贴板，请选中下方正文手动复制。</p><ElInput :model-value="result?.optimizedPrompt ?? ''" type="textarea" :rows="16" readonly aria-label="手动复制图像提示词" /></ElDialog>
    <ElDialog v-model="historyVisible" title="图像提示词历史" width="min(900px, calc(100vw - 24px))">
      <ElAlert v-if="historyError" :title="historyError" type="error" :closable="false" /><ElButton :loading="historyLoading" @click="loadHistory(historyPage.current)">刷新记录</ElButton><ElEmpty v-if="!historyLoading && !historyPage.records.length" description="还没有图像提示词记录。" />
      <article v-for="document in historyPage.records" :key="document.recordId!" class="history-row"><div><ElTag size="small">{{ document.mode === 'CUSTOM_CREATION' ? '新图创作' : '参考图还原' }}</ElTag><span>{{ new Date(document.createdAt).toLocaleString('zh-CN') }}</span><p>{{ document.optimizedPrompt.slice(0, 160) }}</p></div><div><ElButton :disabled="busy" @click="restoreHistory(document)">打开</ElButton><ElButton type="danger" text @click="deleteHistory(document)">删除</ElButton></div></article>
      <ElPagination :current-page="historyPage.current" :page-size="10" :total="historyPage.total" layout="prev, pager, next" :disabled="historyLoading" @current-change="loadHistory" />
    </ElDialog>
  </section>
</template>

<style scoped>
.image-workbench { display: grid; gap: 18px; padding: 24px; }
.image-heading, .heading-actions, .column-heading, .reference-meta, .result-actions, .recovery-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h1, h2, h3 { margin: 0; color: var(--ink-strong); } h1 { font-size: 26px; } h2 { font-size: 17px; } h3 { font-size: 15px; }
p { margin: 8px 0; line-height: 1.8; overflow-wrap: anywhere; }
.image-grid { display: grid; grid-template-columns: minmax(240px, 0.85fr) minmax(300px, 1fr) minmax(300px, 1.15fr); align-items: start; gap: 16px; }
.image-column { padding: 18px; border-radius: var(--radius-md); } .column-heading { margin-bottom: 12px; flex-wrap: wrap; }
.hint { color: var(--ink-muted); font-size: 12px; } .upload-copy { display: grid; gap: 8px; } .upload-copy span, .upload-copy small { color: var(--ink-muted); }
.start-disabled-reason { color: var(--warning); }
.reference-card { margin: 16px 0; padding: 10px; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); }
.reference-card img { display: block; max-width: 100%; max-height: 260px; margin: auto; object-fit: contain; border-radius: var(--radius-sm); }
.reference-meta span { overflow-wrap: anywhere; min-width: 0; } .upload-consent { height: auto; align-items: flex-start; white-space: normal; margin: 12px 0; }
.upload-consent :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; } .upload-consent :deep(.el-checkbox__input) { margin-top: 4px; }
.analysis-section { margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--line-subtle); } .observation { margin-top: 12px; }
.observation > div { display: flex; gap: 8px; align-items: center; } .observation small { color: var(--ink-muted); }
.mode-descriptions { padding: 10px 12px; background: var(--accent-soft); border-radius: var(--radius-sm); font-size: 13px; }
.field { display: grid; gap: 7px; margin-top: 16px; } .field > span { color: var(--ink-strong); font-size: 13px; }
.output-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; min-width: 0; } .primary-action { width: 100%; margin-top: 18px; }
.plan-section { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--line-subtle); }
.plan-question { margin: 16px 0; padding: 12px; min-width: 0; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); } .plan-question legend { font-size: 13px; color: var(--ink-strong); }
.plan-question :deep(.el-radio-group), .plan-question :deep(.el-checkbox-group) { display: grid; gap: 6px; margin-bottom: 10px; }
.plan-question :deep(.el-radio), .plan-question :deep(.el-checkbox) { margin-right: 0; height: auto; white-space: normal; }
.plan-question :deep(.el-radio__label), .plan-question :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
.prompt-text { min-height: 220px; max-height: 660px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.9; color: var(--ink-strong); }
.result-actions { justify-content: flex-start; flex-wrap: wrap; margin-top: 14px; } .result-actions :deep(.el-button + .el-button) { margin-left: 0; }
.result-warnings { padding-left: 18px; color: var(--ink-muted); font-size: 12px; } .history-row { display: flex; justify-content: space-between; gap: 16px; padding: 16px 0; border-bottom: 1px solid var(--line-subtle); }
.history-row > div { min-width: 0; } .history-row span { margin-left: 8px; font-size: 12px; } .history-row p { white-space: pre-wrap; overflow-wrap: anywhere; } .mobile-panes { display: none; }
@media (max-width: 1100px) and (min-width: 901px) { .image-workbench { padding: 16px; } .image-grid { grid-template-columns: minmax(210px, 0.8fr) minmax(250px, 1fr) minmax(250px, 1fr); gap: 10px; } .image-column { padding: 12px; } }
@media (max-width: 900px) { .image-grid { grid-template-columns: minmax(0, 1fr); } .mobile-panes { display: flex; gap: 8px; } .mobile-panes .el-button { flex: 1; margin-left: 0; } .image-grid[data-pane='images'] [data-column]:not([data-column='images']), .image-grid[data-pane='settings'] [data-column]:not([data-column='settings']), .image-grid[data-pane='result'] [data-column]:not([data-column='result']) { display: none; } }
@media (max-width: 640px) { .image-workbench { padding: 12px; } .image-heading { align-items: flex-start; flex-direction: column; } .image-column { padding: 14px; } .history-row { flex-direction: column; } }
</style>
