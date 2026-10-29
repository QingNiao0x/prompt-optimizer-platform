<script setup lang="ts">
import { computed, ref } from 'vue';
import { storeToRefs } from 'pinia';
import { Delete, Document, FolderOpened, UploadFilled, View } from '@element-plus/icons-vue';
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElEmpty, ElInput, ElMessage, ElOption, ElPagination, ElSelect, ElTag, ElUpload, type UploadFile } from 'element-plus';
import { useImagePromptStore, type SelectedSource } from '@/stores/imagePrompt';
import { getApiErrorMessage } from '@/services/http';
import { getProjectFilePath, IGNORED_PATH_SEGMENTS, isSafeRelativeFilePath, isSensitiveFile } from '@/workers/fileReaderCore';
import type { SourcePromptRole } from '@/types/imagePrompt';

const store = useImagePromptStore();
const { sourceFiles, sourceCapabilities, busy } = storeToRefs(store);
const roles: { value: SourcePromptRole; label: string }[] = [
  { value: 'BASELINE', label: '现状依据' }, { value: 'REFERENCE', label: '参考实现／风格' },
  { value: 'FACTS', label: '事实资料' }, { value: 'CONSTRAINTS', label: '要求与约束' }, { value: 'REPLACEMENT', label: '新内容来源' },
];
const fallbackExtensions = 'txt,md,rst,csv,tsv,html,htm,css,scss,js,jsx,ts,tsx,vue,svelte,java,kt,py,go,rs,c,cpp,h,cs,php,rb,sql,json,yaml,yml,xml,toml,ini,properties,conf,cfg,sh,ps1,graphql,proto,doc,docx,pdf,ppt,pptx,xls,xlsx,odt,ods,odp,wps,et,dps'.split(',');
const extensions = computed(() => (sourceCapabilities.value?.extensions ?? fallbackExtensions).map(value => value.replace(/^\./, '').toLowerCase()));
const accept = computed(() => extensions.value.map(value => `.${value}`).join(','));
const selectFile = (file: UploadFile): void => { if (!file.raw) return; try { store.addSourceFiles([file.raw]); } catch (failure: unknown) { ElMessage.warning(getApiErrorMessage(failure)); } };
const pasteVisible = ref(false), pasteName = ref('补充资料.txt'), pasteText = ref('');
const addText = (): void => {
  try { store.addSourceText(pasteName.value.trim() || '补充资料.txt', [{ path: pasteName.value.trim() || '补充资料.txt', content: pasteText.value }]); pasteVisible.value = false; pasteText.value = ''; }
  catch (failure: unknown) { ElMessage.warning(getApiErrorMessage(failure)); }
};
const folderInput = ref<HTMLInputElement>(), folderVisible = ref(false), folderCandidates = ref<File[]>([]), folderSelected = ref<string[]>([]);
const folderTotal = ref(0), folderSkipped = ref(0), folderPage = ref(1);
const folderRows = computed(() => folderCandidates.value.slice((folderPage.value - 1) * 20, folderPage.value * 20));
/** 只枚举用户选择的目录；安全规则复用项目文件入口，未支持/受保护条目明确计数，不宣称完整项目分析。 */
const chooseFolder = (event: Event): void => {
  const input = event.target as HTMLInputElement;
  const selected = Array.from(input.files ?? []); input.value = '';
  folderTotal.value = selected.length; folderSelected.value = []; folderPage.value = 1;
  folderCandidates.value = selected.filter(file => {
    const path = getProjectFilePath(file), basename = path.split('/').at(-1)?.toLowerCase() ?? '';
    const extension = basename.includes('.') ? basename.slice(basename.lastIndexOf('.') + 1) : '';
    return isSafeRelativeFilePath(path) && !isSensitiveFile(path)
      && !path.split('/').some(segment => IGNORED_PATH_SEGMENTS.has(segment.toLowerCase()))
      && (extensions.value.includes(extension) && !/\.(docx?|pdf|pptx?|xlsx?|od[tsp]|wps|et|dps)$/i.test(path) || /(?:^|\/)(dockerfile|makefile|\.env\.example)$/i.test(path));
  });
  folderSkipped.value = selected.length - folderCandidates.value.length; folderVisible.value = true;
};
const addFolder = (): void => {
  const selected = folderCandidates.value.filter(file => folderSelected.value.includes(getProjectFilePath(file)));
  try {
    if (selected.length > (sourceCapabilities.value?.maxEntries ?? 5000)) throw new Error('已勾选文件超过本次集合上限，请缩小范围。');
    if (selected.reduce((total, file) => total + file.size, 0) > 5 * 1024 * 1024) throw new Error('所选源码总量超过5 MiB，请减少范围。');
    store.addSourceText(`${getProjectFilePath(selected[0]!).split('/')[0]} · 已选${selected.length}个文件`, [], selected);
    folderVisible.value = false; folderCandidates.value = []; folderSelected.value = [];
  } catch (failure: unknown) { ElMessage.warning(getApiErrorMessage(failure)); }
};
const reviewing = ref<SelectedSource>(), selectedUnits = ref<string[]>([]), previewId = ref('');
const reviewVisible = computed({ get: () => !!reviewing.value, set: (value: boolean) => { if (!value) reviewing.value = undefined; } });
const preview = computed(() => reviewing.value?.asset?.units.find(unit => unit.id === previewId.value));
const remainingUnits = computed(() => (sourceCapabilities.value?.maxSelectedUnits ?? 40)
  - sourceFiles.value.filter(file => file.key !== reviewing.value?.key).reduce((count, file) => count + file.unitIds.length, 0));
const selectedCharacters = computed(() => reviewing.value?.asset?.units.filter(unit => selectedUnits.value.includes(unit.id)).reduce((sum, unit) => sum + unit.text.length, 0) ?? 0);
const reviewDisabledReason = computed(() => !selectedUnits.value.length ? '请勾选要使用的片段，预览不会自动勾选。'
  : selectedUnits.value.length > remainingUnits.value ? '本次所选片段超过任务上限，请减少范围。'
    : selectedCharacters.value > (sourceCapabilities.value?.maxSelectedCharacters ?? 120000) ? '所选文字超过120000字符，请减少范围。' : '');
const openReview = (file: SelectedSource): void => { reviewing.value = file; selectedUnits.value = [...file.unitIds]; previewId.value = file.asset?.units[0]?.id ?? ''; };
const saveReview = (): void => { if (!reviewing.value || reviewDisabledReason.value) return; store.updateSource(reviewing.value.key, { unitIds: [...selectedUnits.value] }); reviewing.value = undefined; };
</script>

<template>
  <section data-testid="source-materials" class="source-materials" aria-label="代码与文档资料">
    <h3>代码、文档与文本资料</h3>
    <p class="hint">先添加并解析，再审阅要使用的内容。网页可粘贴正文或上传 HTML 快照；不会访问链接、执行源码或运行项目。</p>
    <ElUpload :accept="accept" :auto-upload="false" :show-file-list="false" :disabled="busy" :on-change="selectFile" multiple><ElButton type="primary" plain :icon="UploadFilled" :disabled="busy">添加资料文件</ElButton></ElUpload>
    <div class="source-entry-actions"><ElButton :icon="FolderOpened" :disabled="busy" @click="folderInput?.click()">选择文件夹</ElButton><ElButton :icon="Document" :disabled="busy" @click="pasteVisible = true">粘贴内容</ElButton></div>
    <input ref="folderInput" class="folder-input" type="file" webkitdirectory multiple aria-label="选择资料文件夹" @change="chooseFolder" />
    <p class="hint">每任务最多 {{ sourceCapabilities?.maxAssets ?? 20 }} 份文字资料，单文件50 MiB；最终使用最多 {{ sourceCapabilities?.maxSelectedUnits ?? 40 }} 个片段、120000字符。</p>
    <article v-for="(file, index) in sourceFiles" :key="file.key" class="source-card" :aria-label="`资料${index + 1} ${file.name}`">
      <div class="source-heading"><strong>S{{ index + 1 }} · {{ file.name }}</strong><ElTag size="small" :type="file.asset?.status === 'PARTIAL' ? 'warning' : file.asset ? 'success' : 'info'">{{ file.parsing ? '解析中' : file.asset ? file.asset.status === 'PARTIAL' ? '部分可用' : '可审阅' : '待解析' }}</ElTag></div>
      <p class="hint">{{ file.asset ? `已提取${file.asset.units.length}个片段，已选${file.unitIds.length}个；原件不进入长期历史。` : '仅在浏览器中选择，尚未上传。' }}</p>
      <div class="source-actions"><ElButton v-if="!file.asset" type="primary" :icon="UploadFilled" :loading="file.parsing" :disabled="busy && !file.parsing" @click="store.parseSource(file.key)">解析并预览</ElButton><ElButton v-else type="primary" :plain="!!file.unitIds.length" :icon="View" :disabled="busy" @click="openReview(file)">审阅与选择范围</ElButton><ElButton type="danger" plain :icon="Delete" :disabled="busy && !file.parsing" @click="store.removeSourceFile(file.key)">{{ file.parsing ? '取消并移除' : '移除资料' }}</ElButton></div>
      <div class="source-fields"><label>资料用途<ElSelect :model-value="file.role" :disabled="busy" :aria-label="`${file.name}资料用途`" @update:model-value="store.updateSource(file.key, { role: $event as SourcePromptRole })"><ElOption v-for="role in roles" :key="role.value" :value="role.value" :label="role.label" /></ElSelect></label><label>素材轨道<ElSelect :model-value="file.track" :disabled="busy" :aria-label="`${file.name}素材轨道`" @update:model-value="store.updateSource(file.key, { track: $event as 'REFERENCE' | 'REPLACEMENT' })"><ElOption value="REFERENCE" label="参考资料／现状" /><ElOption value="REPLACEMENT" label="新内容／替换来源" /></ElSelect></label></div>
      <p v-if="file.error" class="warning" role="alert">{{ file.error }}</p><ul v-if="file.asset?.warnings.length" class="warning"><li v-for="warning in file.asset.warnings" :key="warning">{{ warning }}</li></ul>
    </article>
    <ElDialog append-to-body align-center class="source-material-dialog" v-model="pasteVisible" title="添加文本资料" width="min(680px, calc(100vw - 24px))"><p class="hint">仅加入当前草稿。点击“解析并预览”才上传到平台；之后另行确认模型外发。</p><ElInput v-model="pasteName" maxlength="150" aria-label="文本资料名称" /><ElInput v-model="pasteText" type="textarea" :rows="12" maxlength="120000" show-word-limit placeholder="粘贴文档正文、代码、网页正文或配置示例" aria-label="粘贴资料内容" /><template #footer><ElButton @click="pasteVisible = false">取消</ElButton><ElButton type="primary" :disabled="!pasteText.trim()" @click="addText">加入资料草稿</ElButton></template></ElDialog>
    <ElDialog append-to-body align-center class="source-material-dialog" v-model="folderVisible" title="选择文件夹中的资料范围" width="min(800px, calc(100vw - 24px))"><p>发现 {{ folderTotal }} 个文件，其中 {{ folderCandidates.length }} 个可选纯文本文件，{{ folderSkipped }} 个因类型、依赖目录或保护规则未列入。文档附件可单独添加。</p><p class="hint">请勾选本次相关文件。仅解析所选范围，不代表已理解完整项目；未执行源码。每集合最多5000个文件、5 MiB，解析文字最多600000字符。</p><ElEmpty v-if="!folderCandidates.length" description="没有可选纯文本资料，请单独添加受支持文件。" /><ElCheckboxGroup v-model="folderSelected" class="folder-list"><ElCheckbox v-for="file in folderRows" :key="getProjectFilePath(file)" :value="getProjectFilePath(file)">{{ getProjectFilePath(file) }}</ElCheckbox></ElCheckboxGroup><ElPagination v-if="folderCandidates.length > 20" v-model:current-page="folderPage" :page-size="20" :total="folderCandidates.length" layout="prev, pager, next" /><template #footer><span class="hint">已选 {{ folderSelected.length }} 个文件</span><ElButton @click="folderVisible = false">取消</ElButton><ElButton type="primary" :disabled="!folderSelected.length" @click="addFolder">加入所选文件</ElButton></template></ElDialog>
    <ElDialog append-to-body align-center v-model="reviewVisible" title="审阅资料与选择使用范围" width="min(1000px, calc(100vw - 24px))" class="source-material-dialog source-review-dialog"><p>{{ reviewing?.name }}</p><ElAlert title="当前仅在平台完成解析，尚未发送模型。请勾选需要的内容；保存范围后还需单独确认外发。" type="info" :closable="false" /><div class="source-review-grid"><section class="source-selection"><h3>请选择要使用的片段</h3><p role="status">已选 {{ selectedUnits.length }} / {{ Math.max(0, remainingUnits) }} 项，{{ selectedCharacters }} 字符</p><ElCheckboxGroup v-model="selectedUnits"><div v-for="unit in reviewing?.asset?.units" :key="unit.id" class="source-unit" :class="{ selected: selectedUnits.includes(unit.id) }"><ElCheckbox :value="unit.id">{{ unit.path }} · {{ unit.label }}</ElCheckbox><ElButton link type="primary" @click="previewId = unit.id">预览内容</ElButton></div></ElCheckboxGroup></section><section class="source-preview"><h3>内容预览</h3><p class="hint">{{ preview?.path }} · {{ preview?.label }}</p><pre>{{ preview?.text ?? '选择片段查看实际提取内容。' }}</pre></section></div><p v-for="warning in reviewing?.asset?.warnings" :key="warning" class="warning">{{ warning }}</p><template #footer><p v-if="reviewDisabledReason" class="warning" role="status">{{ reviewDisabledReason }}</p><ElButton @click="reviewing = undefined">取消修改</ElButton><ElButton type="primary" :disabled="!!reviewDisabledReason" @click="saveReview">保存使用范围</ElButton></template></ElDialog>
  </section>
</template>

<style scoped>
.source-materials { min-width: 0; padding-bottom: 18px; border-bottom: 1px solid var(--line-strong); } h3 { margin: 0; font-size: 15px; color: var(--ink-strong); }
p { line-height: 1.75; overflow-wrap: anywhere; } .hint { font-size: 12px; color: var(--ink-muted); } .warning { color: var(--warning); font-size: 13px; overflow-wrap: anywhere; } .folder-input { display: none; }
.source-entry-actions, .source-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; } .source-actions :deep(.el-button), .source-entry-actions :deep(.el-button) { margin: 0; min-height: 44px; height: auto; white-space: normal; }
.source-card { margin-top: 16px; padding: 14px; border: 1px solid var(--line-strong); border-radius: var(--radius-md); background: var(--surface-panel); min-width: 0; }
.source-heading { display: flex; gap: 8px; align-items: flex-start; justify-content: space-between; } .source-heading strong { min-width: 0; overflow-wrap: anywhere; font-size: 14px; } .source-heading .el-tag { flex-shrink: 0; }
.source-fields { display: grid; gap: 10px; margin-top: 12px; } .source-fields label { display: grid; gap: 6px; color: var(--ink-muted); font-size: 12px; }
.folder-list { display: grid; margin: 12px 0; } .folder-list :deep(.el-checkbox), .source-unit :deep(.el-checkbox) { height: auto; min-height: 44px; white-space: normal; margin: 0; }
.folder-list :deep(.el-checkbox__label), .source-unit :deep(.el-checkbox__label) { min-width: 0; white-space: normal; overflow-wrap: anywhere; line-height: 1.6; }
.source-review-grid { display: grid; grid-template-columns: minmax(240px, .85fr) minmax(0, 1.15fr); gap: 18px; margin-top: 16px; align-items: start; }
.source-selection { padding: 14px; border: 2px solid var(--accent); border-radius: var(--radius-md); background: var(--accent-soft); min-width: 0; max-height: 55vh; overflow: auto; }
.source-unit { display: grid; gap: 4px; padding: 8px; margin-top: 8px; border: 1px solid var(--line-strong); border-radius: var(--radius-sm); background: var(--surface-panel); } .source-unit.selected { border-color: var(--accent); background: var(--glass-bg-strong); } .source-unit .el-button { justify-self: start; min-height: 36px; }
.source-preview { min-width: 0; } .source-preview pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.7; padding: 14px; max-height: 50vh; overflow: auto; color: var(--ink-strong); border: 1px solid var(--line-subtle); background: var(--surface-panel); border-radius: var(--radius-sm); }
/* 弹窗移至body后脱离玻璃面板的层叠上下文；标题与操作常显，长内容在弹窗内滚动。 */
:global(.source-material-dialog) { display: flex; flex-direction: column; max-height: calc(100dvh - 32px); margin: 0 auto; }
:global(.source-material-dialog .el-dialog__body) { min-height: 0; overflow-y: auto; }
:global(.source-material-dialog .el-dialog__header), :global(.source-material-dialog .el-dialog__footer) { flex-shrink: 0; white-space: normal; }
 .el-textarea { margin-top: 12px; }
@media (max-width: 640px) { .source-review-grid { grid-template-columns: minmax(0, 1fr); } .source-selection { max-height: 36vh; } .source-preview pre { max-height: 30vh; } .source-actions .el-button:first-child { flex: 1 0 100%; } }
</style>
