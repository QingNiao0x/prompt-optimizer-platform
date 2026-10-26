<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue';
import {
  ElAlert, ElButton, ElCheckbox, ElDialog, ElEmpty, ElIcon, ElInput, ElMessage,
  ElMessageBox, ElOption, ElPagination, ElSelect, ElSwitch, ElTag, ElUpload,
  type UploadFile,
} from 'element-plus';
import ImagePromptHelp from '@/components/imageprompt/ImagePromptHelp.vue';
import ImagePromptPlanPanel from '@/components/imageprompt/ImagePromptPlanPanel.vue';
import ImagePromptMediaCard from '@/components/imageprompt/ImagePromptMediaCard.vue';
import ImagePromptRelations from '@/components/imageprompt/ImagePromptRelations.vue';
import GuestTrialBanner from '@/components/auth/GuestTrialBanner.vue';
import { useImagePromptStore } from '@/stores/imagePrompt';
import { useAuthStore } from '@/stores/auth';
import { useTrialStore } from '@/stores/trial';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getApiErrorMessage } from '@/services/http';
import type { ImagePromptDocument, ImagePromptHistoryPage, ImageReferenceRole, ImagePromptAnswer, ImagePromptMaterialTrack, ImagePromptMaterialSource } from '@/types/imagePrompt';
import { imagePromptPlanProgress } from '@/features/imageprompt/planPresentation';
import { formatModelVersion } from '@/features/models/modelVersion';

const store = useImagePromptStore();
const route = useRoute();
const router = useRouter();
const trial = useTrialStore();
const { mode, modelId, creationIntent, exactText, context, language, targetTool, files, capabilities, availableModels,
  selectedModel, modelInputError, plan, answers, result, editDraft, isEditing, phase, error, cleanupWarning, recoveryId,
  recoveryPending, consent, busy, hasDraftChanges, canStart, startDisabledReason, undoDocument,
  outputType, mediaFiles, activeMediaFiles, mediaCapabilities, relations, selectedUnitCount } = storeToRefs(store);
const creation = computed(() => mode.value === 'CUSTOM_CREATION');
const outputLabel = computed(() => ({ IMAGE: '新图', VIDEO: '视频', PPT: 'PPT' }[outputType.value]));
const planProgress = computed(() => imagePromptPlanProgress(plan.value, answers.value));
const planPanel = ref<InstanceType<typeof ImagePromptPlanPanel>>();
const requirementsHeading = ref<HTMLElement>();
const planReadyNotice = ref(false), highlightPlan = ref(false);
let highlightTimer: ReturnType<typeof setTimeout> | undefined;
const rewriteInstruction = ref('');
const historyVisible = ref(false);
const historyPage = ref<ImagePromptHistoryPage>({ records: [], total: 0, size: 10, current: 1, pages: 0 });
const historyLoading = ref(false);
const historyError = ref('');
const manualCopyVisible = ref(false);
const pane = ref(route.query.pane === 'result' && store.result ? 'result' : 'images');
const phaseLabel = computed(() => ({ IDLE: '', UPLOADING: '上传并校验图片中', PLANNING: '梳理创作意图中',
  GENERATING: '生成提示词中', SAVING: '保存正文中', RECOVERING: '查询处理结果中' }[phase.value]));
const analysis = computed(() => plan.value?.analysis ?? result.value?.analysis ?? []);
const materialSources = computed(() => plan.value?.materialSources ?? result.value?.materialSources ?? []);
const observationSource = (id: string): string => result.value?.sources.find(source => source.id === id)?.name
  ?? files.value.find(file => file.asset?.id === id)?.name
  ?? materialSources.value.find(source => source.units.some(unit => unit.id === id))?.name ?? '参考图片';
const sourceLabel = (source: ImagePromptMaterialSource, sources: ImagePromptMaterialSource[]): string => `${source.track === 'REFERENCE' ? 'R' : 'T'}${sources.filter(item => item.track === source.track).indexOf(source) + 1}`;
const trackFiles = (track: ImagePromptMaterialTrack) => mediaFiles.value.filter(file => file.track === track);
const tracks: { id: ImagePromptMaterialTrack; title: string; description: string; testId: string }[] = [
  { id: 'REFERENCE', title: '参考素材——借鉴什么', description: '提供风格、配色、构图、版式或镜头安排的来源。', testId: 'reference-media' },
  { id: 'REPLACEMENT', title: '替换素材——新主体／新内容', description: '提供最终希望出现的新产品、人物或内容；也可在替换关系中直接填写文字。', testId: 'replacement-media' },
];
const visibleTracks = computed(() => creation.value ? tracks : tracks.filter(track => track.id === 'REFERENCE'));
const retainedReplacementCount = computed(() => creation.value ? 0 : mediaFiles.value.filter(file => file.track === 'REPLACEMENT').length);
const referenceAccept = computed(() => auth.isAuthenticated ? 'image/jpeg,image/png,video/mp4,video/quicktime,video/webm,.pptx' : 'image/jpeg,image/png');
const downstreamToolLabel = computed(() => creation.value ? '下游创作工具' : activeMediaFiles.value.length ? '下游使用工具' : '下游绘图工具');
let active = true;
let historyEpoch = 0;
let historyLoad = 0;
const auth = useAuthStore();
// 账号历史可能保存在数据库或服务内存；是否开放历史操作由身份而非存储介质决定。
const accountResult = computed(() => auth.isAuthenticated);
const register = (reason: 'history' | 'plan' = 'history'): void => {
  void router.push({ path: '/login', query: { mode: 'register', reason, redirect: route.path } });
};
// 页面私有历史和迟到响应也必须随身份隔离；只清 Pinia 不能撤回已发出的请求。
watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), () => {
  historyEpoch += 1; historyLoad += 1; historyVisible.value = false; historyLoading.value = false;
  historyPage.value = { records: [], total: 0, size: 10, current: 1, pages: 0 };
  historyError.value = ''; rewriteInstruction.value = ''; manualCopyVisible.value = false;
}, { flush: 'sync' });

/** 切换只变更业务输入；旧结果和编辑稿保留，不自动上传或调用模型。 */
const togglePlan = (value: string | number | boolean): void => {
  if (value && !auth.isAuthenticated) { register('plan'); return; }
  mode.value = value ? 'CUSTOM_CREATION' : 'REFERENCE_RECONSTRUCTION';
};
const selectFile = (file: UploadFile): void => {
  if (!file.raw) return;
  try { store.addFiles([file.raw]); } catch (failure: unknown) { ElMessage.warning(getApiErrorMessage(failure)); }
};
const selectMedia = (file: UploadFile, track: ImagePromptMaterialTrack): void => {
  if (!file.raw) return;
  try { store.addMediaFiles([file.raw], track); } catch (failure: unknown) { ElMessage.warning(getApiErrorMessage(failure)); }
};
/** 单张图片沿用快捷提取；其他媒体先建立可审阅页/帧，不由文件类型决定是否进入Plan。 */
const selectReference = (file: UploadFile): void => {
  if (!file.raw) return;
  if (!auth.isAuthenticated || ['image/jpeg', 'image/png'].includes(file.raw.type) || /\.(png|jpe?g)$/i.test(file.name)) selectFile(file);
  else selectMedia(file, 'REFERENCE');
};
/** 顶栏在窄桌面会换成双行；本页所有问答定位使用实际高度，避免固定90px遮住标题或未答题。 */
const positionElement = (element: HTMLElement): void => {
  const topbar = document.querySelector<HTMLElement>('header.topbar');
  // 使用顶栏实际可见边界，兼容双行桌面导航与手机布局。
  const navigationBottom = Math.max(0, topbar?.getBoundingClientRect().bottom ?? 0);
  const anchor = element.closest<HTMLElement>('[data-position-anchor]') ?? element;
  window.scrollTo({ top: Math.max(0, window.scrollY + anchor.getBoundingClientRect().top - navigationBottom - 16),
    behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  element.focus({ preventScroll: true });
};
const positionPlan = async (): Promise<void> => {
  // 先撤下就绪提示再测量；滚动后才撤提示会令卡片上移并重新被顶栏遮挡。
  pane.value = 'settings'; planReadyNotice.value = false; await nextTick(); planPanel.value?.focus();
};
const editRequirements = async (): Promise<void> => {
  pane.value = 'settings'; await nextTick(); if (requirementsHeading.value) positionElement(requirementsHeading.value);
};
const updateAnswer = (id: string, answer: ImagePromptAnswer): void => { answers.value[id] = answer; };
watch(() => plan.value?.id, id => { planReadyNotice.value = Boolean(id); }, { flush: 'sync' });
/** 仅主动取得新Plan后定位一次；用户转而编辑或打开弹窗时保留当前焦点。 */
const start = async (): Promise<void> => {
  const previous = plan.value?.id;
  let editing = false;
  const preserveEditing = (event: FocusEvent): void => { if (event.target instanceof HTMLElement && event.target.matches('input, textarea, [contenteditable="true"]')) editing = true; };
  document.addEventListener('focusin', preserveEditing);
  try { await store.start(); }
  finally { document.removeEventListener('focusin', preserveEditing); }
  if (!active) return;
  if (plan.value && plan.value.id !== previous && !historyVisible.value && !manualCopyVisible.value && !editing) {
    highlightPlan.value = true; clearTimeout(highlightTimer); highlightTimer = setTimeout(() => { highlightPlan.value = false; }, 800);
    await positionPlan();
  } else if (!plan.value && result.value) pane.value = 'result';
};
const confirm = async (): Promise<void> => { await store.confirm(); if (active && result.value && !plan.value) pane.value = 'result'; };
const loadHistory = async (current = 1): Promise<void> => {
  if (!auth.isAuthenticated) { register(); return; }
  const epoch = historyEpoch, load = ++historyLoad;
  historyLoading.value = true; historyError.value = '';
  try { const value = await imagePromptApi.history(current, 10); if (active && epoch === historyEpoch && load === historyLoad) historyPage.value = value; }
  catch (failure: unknown) { if (active && epoch === historyEpoch && load === historyLoad) historyError.value = getApiErrorMessage(failure); }
  finally { if (active && epoch === historyEpoch && load === historyLoad) historyLoading.value = false; }
};
const openHistory = (): void => {
  if (!auth.isAuthenticated) { register(); return; }
  historyVisible.value = true; void loadHistory();
};
const restoreHistory = async (document: ImagePromptDocument): Promise<void> => {
  if (!auth.isAuthenticated) { register(); return; }
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
  if (!auth.isAuthenticated) { register(); return; }
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
onMounted(() => {
  // 访客不恢复旧 Plan 模式，也不因历史深链接发起任何历史读取请求。
  if (!auth.isAuthenticated) {
    mode.value = 'REFERENCE_RECONSTRUCTION';
    void trial.initialize();
    if (route.query.history === '1' || typeof route.query.record === 'string') { register(); return; }
  }
  void store.refreshModels();
  void store.refreshMediaCapabilities();
  if (route.query.history === '1') openHistory();
});
onBeforeUnmount(() => { active = false; clearTimeout(highlightTimer); store.cancelPending(); });
</script>

<template>
  <section class="image-workbench">
    <header class="image-heading">
      <div><h1>图像转提示词</h1><p>关闭 Plan，还原参考素材的可见内容；开启 Plan，从构想与素材出发定制图片、视频或 PPT 提示词。</p></div>
      <div class="heading-actions"><ElButton @click="openHistory">图像历史</ElButton><ElButton :disabled="busy" @click="clearTask">新建任务</ElButton></div>
    </header>
    <GuestTrialBanner feature="IMAGE_TO_PROMPT" />
    <ElAlert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <ElAlert v-if="cleanupWarning" type="warning" :title="cleanupWarning" :closable="false" />
    <ElAlert v-if="selectedModel?.mock" type="warning" title="当前使用演示模型，不具备真实视觉识别能力。选择已登记视觉能力的真实模型后可进行识图。" :closable="false" show-icon />
    <ElAlert v-if="auth.isAuthenticated && capabilities && !capabilities.persistentHistory" type="info" title="本机演示历史保存在服务内存中，服务重启后不会保留。" :closable="false" />
    <div v-if="recoveryId && recoveryPending" class="recovery-actions"><span>该次请求的结果尚未确认。</span><ElButton :loading="busy" @click="store.recover">查询处理结果</ElButton></div>
    <div v-if="plan && planReadyNotice" class="plan-ready-notice" role="status"><span>{{ planProgress.missing.length ? `创作问题已就绪，还有 ${planProgress.missing.length} 项必答。` : '创作方向已就绪，等待你确认生成。' }}</span><ElButton type="primary" plain @click="positionPlan">{{ planProgress.missing.length ? '前往回答' : '前往确认' }}</ElButton></div>
    <nav class="mobile-panes" aria-label="图像工作台分区">
      <ElButton v-for="item in [{ id: 'images', label: '图片' }, { id: 'settings', label: '设置' }, { id: 'result', label: '结果' }]"
        :key="item.id" :type="pane === item.id ? 'primary' : 'default'" :aria-pressed="pane === item.id" @click="pane = item.id">{{ item.label }}<small v-if="item.id === 'settings' && plan" class="pane-plan-status">{{ planProgress.missing.length ? `待回答 ${planProgress.missing.length}` : '待确认' }}</small></ElButton>
    </nav>
    <div class="workbench-grid image-grid" :data-pane="pane">
      <section class="glass-panel image-column" data-column="images" aria-labelledby="reference-images-title">
        <div class="column-heading"><h2 id="reference-images-title">{{ creation ? '创作素材（选填）' : activeMediaFiles.length ? '参考素材' : '参考图片' }}</h2><ElTag effect="plain">{{ files.length + mediaFiles.length }} / {{ auth.isAuthenticated ? mediaCapabilities?.maxAssets ?? 6 : 1 }}</ElTag></div>
        <p class="hint">{{ creation ? '也可以只写构想。附图时，请指定借鉴对象、风格还是布局。' : auth.isAuthenticated ? '选择图片、PPTX 或视频，直接提取所选画面内容，无需开启 Plan 或回答创作问题。' : '选择一张参考图，提取实际可见的画面内容，不发起创作问答。登录后也可使用 PPTX 和视频。' }}</p>
        <ElUpload v-if="!auth.isAuthenticated" drag accept="image/jpeg,image/png" :auto-upload="false" :show-file-list="false" :disabled="busy" :on-change="selectFile" aria-label="选择参考图片">
          <div class="upload-copy"><strong>拖入图片，或点击选择</strong><span>JPEG / PNG · 单张不超过 10 MiB</span><small>最多 1500 万像素，单边 8192 像素</small></div>
        </ElUpload>
        <template v-if="auth.isAuthenticated"><section v-for="track in visibleTracks" :key="track.id" :data-testid="track.testId" class="media-track" :aria-label="creation ? track.title : '参考素材'">
          <template v-if="creation"><h3>{{ track.title }}</h3><p class="hint">{{ track.description }}</p></template>
          <ElUpload drag :accept="referenceAccept" :auto-upload="false" :show-file-list="false" :disabled="busy" :on-change="file => creation ? selectMedia(file, track.id) : selectReference(file)" :aria-label="creation ? track.id === 'REFERENCE' ? '选择参考素材' : '选择替换素材' : '选择参考图片或多媒体素材'">
            <div class="upload-copy"><strong>{{ creation ? track.id === 'REFERENCE' ? '添加参考素材' : '添加新主体／新内容' : '拖入图片、PPTX 或视频，或点击选择' }}</strong><span>JPEG / PNG · 视频片段 · PPTX</span><small>单文件最多 50 MiB，图片最多 10 MiB</small></div>
          </ElUpload>
          <ImagePromptMediaCard v-for="(file, index) in trackFiles(track.id)" :key="file.key" :file="file" :label="`${track.id === 'REFERENCE' ? 'R' : 'T'}${index + 1}`" :busy="busy" :max-units="Math.max(0, (mediaCapabilities?.maxSelectedUnits ?? 12) - files.length - selectedUnitCount + file.units.length)" @parse="store.parseMedia(file.key)" @remove="store.removeMediaFile(file.key)" @update="store.updateMedia(file.key, $event)" />
        </section><p class="hint">{{ creation ? '图片、视频、PPTX 都可作为参考或新内容。' : 'PPTX 和视频先上传到平台解析，再审阅并选择要识别的页／帧；普通图片仍可快捷提取。' }}每任务最多 {{ mediaCapabilities?.maxAssets ?? 6 }} 份素材、选中 {{ mediaCapabilities?.maxSelectedUnits ?? 12 }} 个图片／页／帧。视频每段最多 30 秒。</p><p v-if="mediaCapabilities && !mediaCapabilities.videoAvailable" class="hint start-disabled-reason">当前部署尚未配置视频解码工具；图片和 PPTX 可正常解析。</p></template>
        <p v-if="retainedReplacementCount" class="hint">已保留 {{ retainedReplacementCount }} 份替换素材，开启 Plan 后可继续使用；它们不参与本次还原。</p>
        <p v-if="creation && files.length" class="hint">以下图片保留自参考图还原模式。需要按页／帧关联替换关系时，可移除后添加到上方素材区。</p>
        <article v-for="file in files" :key="file.key" class="reference-card">
          <img :src="file.previewUrl" :alt="file.name" />
          <div class="reference-meta"><span>{{ file.name }}</span><ElButton text type="danger" :disabled="busy" @click="store.removeFile(file.key)">移除</ElButton></div>
          <ElSelect v-if="creation" :model-value="file.role" :aria-label="`${file.name}的参考用途`" :disabled="busy" @update:model-value="store.changeRole(file.key, $event as ImageReferenceRole)">
            <ElOption label="整体灵感" value="GENERAL" /><ElOption label="只参考主体" value="SUBJECT" /><ElOption label="只参考风格与配色" value="STYLE" /><ElOption label="只参考布局" value="LAYOUT" />
          </ElSelect>
          <p v-if="file.asset" class="hint">{{ file.asset.processing.join('；') }}。临时图片保留约一小时。</p><p v-else class="hint">仅本地预览，尚未上传。</p>
        </article>
        <ElAlert v-if="!creation && !activeMediaFiles.length && files.length > 1" title="快捷参考图最多保留一张，请移除多余图片；PPTX 和视频等参考素材可另外添加。" type="warning" :closable="false" />
        <p class="hint">原图使用私有临时存储，不进入长期历史；历史中的文字与分析摘要也可能包含图片内容，请自行核对。无需单独 OCR 服务。</p>
        <div class="analysis-section"><h3>{{ plan ? '本次 Plan 的视觉分析' : result ? '已生成结果的视觉分析' : '视觉分析' }}</h3>
          <p v-if="!analysis.length" class="hint">{{ creation && !files.length && !activeMediaFiles.length ? '纯文字创作不进行图片分析。' : '实际分析结果返回后显示，文件预览不代表已识图。' }}</p>
          <article v-for="(item, index) in analysis" :key="`${item.imageId}-${index}`" class="observation">
            <div><ElTag size="small" :type="item.status === 'UNCERTAIN' ? 'warning' : 'info'">{{ item.category }}</ElTag><small>{{ item.status === 'UNCERTAIN' ? '待核对' : item.status === 'INTERPRETATION' ? '风格判断' : '可见观察' }}</small></div>
            <p>{{ item.description }}</p><small>来源：{{ observationSource(item.imageId) }}</small>
          </article>
        </div>
      </section>
      <section class="glass-panel image-column" data-column="settings" aria-labelledby="image-creation-title">
        <div class="column-heading"><h2 id="image-creation-title">模式与要求</h2><ElSwitch :model-value="creation" active-text="Plan 开启" inactive-text="关闭" aria-label="Plan 创作模式" :disabled="busy || !auth.isAuthenticated" @change="togglePlan" /></div>
        <ImagePromptPlanPanel v-if="plan && auth.isAuthenticated" ref="planPanel" :plan="plan" :answers="answers" :busy="busy" :recovery-pending="recoveryPending" :has-draft-changes="hasDraftChanges" :output-label="outputLabel" :highlight="highlightPlan" @answer="updateAnswer" @confirm="confirm" @edit-requirements="editRequirements" @position="positionElement" />
        <p v-if="!auth.isAuthenticated" class="hint">免费体验支持参考图还原；Plan 创作模式需注册登录。<ElButton link type="primary" @click="register('plan')">注册使用 Plan</ElButton></p>
        <div class="mode-descriptions"><p><strong>关闭：参考内容还原。</strong>描述图片、所选 PPT 页面或视频帧的可见内容，不发起创作问答。</p><p><strong>开启：从无到有。</strong>确认构想与替换关系，生成供下游创作图片、视频或 PPT 的提示词。参考素材可选。</p><p>切换模式会保留已添加素材、所选范围和遮挡；上传不会自动进入 Plan。</p></div>
        <label v-if="creation" class="field"><span>希望下游制作什么？</span><ElSelect v-model="outputType" :disabled="busy" aria-label="目标成品类型"><ElOption label="图片" value="IMAGE" /><ElOption label="视频" value="VIDEO" /><ElOption label="PPT 演示文稿" value="PPT" /></ElSelect></label>
        <label class="field"><span>本次模型</span><ElSelect v-model="modelId" :disabled="busy" placeholder="选择本次可用模型" aria-label="图像任务模型"><ElOption v-for="model in availableModels" :key="model.id" :label="`${formatModelVersion(model.displayName)}${model.mock ? '（演示）' : model.vision ? '（视觉）' : '（纯文字）'}`" :value="model.id" /></ElSelect></label>
        <p class="hint">与提示词优化共用模型目录，由模型管理统一配置。带图需开放视觉能力；无图 Plan 可使用文字模型。<ElButton link type="primary" :disabled="busy" :loading="store.isLoadingModels" @click="store.refreshModels">刷新模型</ElButton></p>
        <ElAlert v-if="modelInputError" :title="modelInputError" type="info" :closable="false" />
        <p v-if="!availableModels.length && !store.isLoadingModels" class="hint">当前没有可用模型，请刷新或联系管理员。</p>
        <section v-if="creation" data-testid="creative-supplement-card" data-position-anchor class="creative-supplement" aria-labelledby="creative-supplement-title"><h3 id="creative-supplement-title" ref="requirementsHeading" tabindex="-1">自主补充创作要求</h3><p class="hint">先描述创作构想。你还可以补充风格、构图、要保留或替换的内容；其余字段选填，没有明确偏好也可继续。</p>
          <label class="field"><span>想创作什么？（必填）</span><ElInput v-model="creationIntent" type="textarea" :rows="4" maxlength="6000" show-word-limit :disabled="busy" placeholder="例如：参考R1的暖色和构图，将中央主体替换为T1的新产品；其他细节委托 AI 设计。" aria-label="新图创作构想" /></label>
          <label class="field"><span>画面必须出现的文字（选填，逐字保留）</span><ElInput v-model="exactText" type="textarea" :rows="2" maxlength="2000" :disabled="busy" placeholder="单独填写标题、卡片文字等，不与风格选择混在一起。" aria-label="画面指定文案" /></label>
          <label class="field"><span>项目或品牌背景（选填）</span><ElInput v-model="context" type="textarea" :rows="2" maxlength="6000" :disabled="busy" placeholder="只填写本次需要的品牌规范、产品事实或设计约束；不会自动读取源码。" aria-label="图像项目上下文" /></label>
          <p v-if="plan" class="hint">修改这些要求会使当前 Plan 失效，需要重新梳理创作意图。</p>
        </section>
        <ImagePromptRelations v-if="creation" v-model="relations" :files="mediaFiles" :disabled="busy" />
        <div class="output-fields"><label class="field"><span>提示词语言</span><ElSelect v-model="language" :disabled="busy" aria-label="提示词语言"><ElOption label="中文" value="ZH" /><ElOption label="英文" value="EN" /></ElSelect></label><label class="field"><span>{{ downstreamToolLabel }}</span><ElInput v-model="targetTool" maxlength="100" :disabled="busy" :aria-label="downstreamToolLabel" placeholder="填写下游使用的工具" /></label></div>
        <!-- 确认与生成操作放在同一区域，桌面和窄屏都不必回到图片预览下寻找勾选项。 -->
        <section v-if="files.length || activeMediaFiles.length" class="consent-panel" :class="{ 'is-confirmed': consent }" aria-labelledby="image-consent-title">
          <div class="consent-heading"><div class="consent-heading-title"><ElIcon aria-hidden="true"><CircleCheckFilled v-if="consent" /><WarningFilled v-else /></ElIcon><h3 id="image-consent-title">生成前请确认图片隐私</h3></div><ElTag size="small" :type="consent ? 'success' : 'warning'" effect="plain">{{ consent ? '已确认' : '待确认' }}</ElTag></div>
          <p id="image-consent-guidance" class="consent-guidance" role="status">{{ consent ? '隐私确认已完成；请核对本次接收模型与所选范围。' : '请在下方勾选同意，完成本次模型外发确认。' }}</p>
          <ElCheckbox v-model="consent" :disabled="busy" class="upload-consent" aria-describedby="image-consent-guidance"><strong>我已检查图片隐私{{ activeMediaFiles.length ? '与素材内容' : '' }}</strong>，同意点击操作后将{{ activeMediaFiles.length ? '所选页／帧的遮挡后图片与允许的结构文字' : '所选图片' }}<template v-if="activeMediaFiles.length && files.length">及另外选择的参考图片</template>发送至平台后端与所选模型服务。</ElCheckbox>
          <p v-if="activeMediaFiles.length" class="hint">接收模型：{{ selectedModel?.displayName ?? '尚未选择' }}；所选范围 {{ selectedUnitCount }} 个页／帧<template v-if="files.length">，另有 {{ files.length }} 张参考图片</template>。按模型图片上限分批识别，{{ creation ? 'Plan 与最终生成' : '素材识别与提示词提取' }}也可能产生调用费用；未选范围不外发。音轨不发送。</p>
        </section>
        <ElButton :type="plan ? 'default' : 'primary'" class="primary-action" :loading="busy && phase !== 'SAVING'" :disabled="!canStart" @click="start">{{ creation ? plan ? '重新梳理创作意图' : '梳理创作意图' : activeMediaFiles.length ? '提取参考素材提示词' : '提取参考图提示词' }}</ElButton>
        <p v-if="phaseLabel" role="status" class="hint">{{ phaseLabel }}</p>
        <p v-if="!busy && startDisabledReason && startDisabledReason !== modelInputError" role="status" class="hint start-disabled-reason">{{ startDisabledReason }}</p>
      </section>
      <section class="glass-panel image-column result-column" data-column="result" aria-labelledby="image-result-title">
        <div class="column-heading"><h2 id="image-result-title">提示词结果</h2><ElTag v-if="result" effect="plain">{{ result.mode === 'CUSTOM_CREATION' ? '新图创作' : result.materialSources?.length ? '参考素材还原' : '参考图还原' }}</ElTag></div>
        <ElEmpty v-if="!result" description="生成后在这里核对、编辑和复制提示词。" />
        <template v-else>
          <p class="hint">{{ result.modelName }}{{ result.mock ? ' · 演示结果' : '' }} · {{ result.sources.length || result.materialSources?.length ? `${result.sources.length + (result.materialSources?.length ?? 0)} 份素材来源` : '纯文字创作' }}</p>
          <ElAlert v-if="accountResult && !result.historySaved" title="提示词已生成，历史尚未保存。可以复制当前正文或重试保存，不会重复调用模型。" type="warning" :closable="false" /><ElButton v-if="accountResult && !result.historySaved" :loading="phase === 'SAVING'" @click="store.retrySave">重试保存历史</ElButton>
          <p v-if="!accountResult" class="hint">本次结果不保存到账号历史。可在当前页面编辑和复制，请及时保留正文。</p>
          <ElInput v-if="isEditing" v-model="editDraft" type="textarea" :rows="18" maxlength="30000" show-word-limit aria-label="编辑图像提示词" /><pre v-else class="prompt-text">{{ result.optimizedPrompt }}</pre>
          <div class="result-actions"><template v-if="isEditing"><ElButton type="primary" :loading="phase === 'SAVING'" :disabled="busy || !editDraft.trim()" @click="store.saveEdit()">{{ accountResult ? '保存正文' : '保留本页修改' }}</ElButton><ElButton :disabled="busy" @click="cancelEdit">取消编辑</ElButton></template><template v-else><ElButton type="primary" @click="copy">复制提示词</ElButton><ElButton :disabled="busy || accountResult && !result.recordId" @click="isEditing = true; editDraft = result.optimizedPrompt">编辑</ElButton><ElButton :disabled="busy || !undoDocument || accountResult && !result.recordId" @click="store.undo">撤销上次修改</ElButton></template></div>
          <ul v-if="result.warnings.length" class="result-warnings"><li v-for="warning in result.warnings" :key="warning">{{ warning }}</li></ul>
          <section v-if="result.materialSources?.length" class="material-manifest" aria-label="素材对应表"><h3>素材对应表</h3><p class="hint">请在下游工具重新上传对应素材，并按提示词说明其用途。内部素材编号不能让下游直接读取文件。</p><article v-for="source in result.materialSources" :key="source.assetId"><strong>{{ sourceLabel(source, result.materialSources) }} · {{ source.name }}</strong><p class="hint">{{ source.track === 'REFERENCE' ? '参考来源' : '新主体／新内容' }} · {{ source.kind }} · 已选 {{ source.units.length }} 个页／帧</p><p v-for="warning in source.warnings" :key="warning" class="hint">{{ warning }}</p></article></section>
          <p v-if="result.sources.length || result.materialSources?.length" class="hint">历史不保存原始素材。已有正文可以继续使用；需要重新识别时，请重新选择参考素材。</p>
          <template v-if="accountResult"><label class="field"><span>基于当前正文再次优化</span><ElInput v-model="rewriteInstruction" type="textarea" :rows="2" maxlength="3000" :disabled="busy" :placeholder="result.mode === 'REFERENCE_RECONSTRUCTION' ? '仅整理表达。需要新增内容或改变场景，请开启 Plan。' : '描述希望如何调整当前创作提示词。'" aria-label="图像提示词再次优化要求" /></label>
            <ElButton :disabled="busy || hasDraftChanges || !result.recordId || !rewriteInstruction.trim() || recoveryPending" @click="store.rewrite(rewriteInstruction)">基于已有文字优化</ElButton><p class="hint">此操作使用已保存正文和分析摘要，不会重新读取图片。</p>
          </template>
          <p v-else-if="!auth.isAuthenticated" class="hint">注册登录后可使用历史及历史正文再次优化。<ElButton link type="primary" @click="register()">注册账号</ElButton></p>
        </template>
      </section>
    </div>
    <ImagePromptHelp />
    <ElDialog v-model="manualCopyVisible" title="手动复制提示词" width="min(680px, calc(100vw - 24px))"><p>浏览器未允许写入剪贴板，请选中下方正文手动复制。</p><ElInput :model-value="result?.optimizedPrompt ?? ''" type="textarea" :rows="16" readonly aria-label="手动复制图像提示词" /></ElDialog>
    <ElDialog v-if="auth.isAuthenticated" v-model="historyVisible" title="图像提示词历史" width="min(900px, calc(100vw - 24px))">
      <ElAlert v-if="historyError" :title="historyError" type="error" :closable="false" /><ElButton :loading="historyLoading" @click="loadHistory(historyPage.current)">刷新记录</ElButton><ElEmpty v-if="!historyLoading && !historyPage.records.length" description="还没有图像提示词记录。" />
      <article v-for="document in historyPage.records" :key="document.recordId!" class="history-row"><div><ElTag size="small">{{ document.mode === 'CUSTOM_CREATION' ? '新图创作' : document.materialSources?.length ? '参考素材还原' : '参考图还原' }}</ElTag><span>{{ new Date(document.createdAt).toLocaleString('zh-CN') }}</span><p>{{ document.optimizedPrompt.slice(0, 160) }}</p></div><div><ElButton :disabled="busy" @click="restoreHistory(document)">打开</ElButton><ElButton type="danger" text @click="deleteHistory(document)">删除</ElButton></div></article>
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
.reference-meta span { overflow-wrap: anywhere; min-width: 0; }
.consent-panel { margin-top: 18px; padding: 16px; min-width: 0; border: 2px solid var(--warning); border-radius: var(--radius-md); background: var(--orange-soft); }
.consent-panel.is-confirmed { border-color: var(--success); background: var(--green-soft); }
.consent-heading { display: flex; align-items: flex-start; flex-wrap: wrap; justify-content: space-between; gap: 10px; }
.consent-heading-title { display: flex; align-items: flex-start; min-width: 0; gap: 8px; }
.consent-heading-title .el-icon { flex-shrink: 0; margin-top: 1px; font-size: 20px; color: var(--warning); }
.consent-panel.is-confirmed .consent-heading-title .el-icon { color: var(--success); }
.consent-heading .el-tag { flex-shrink: 0; }
.consent-heading h3 { margin: 0; color: var(--ink-strong); font-size: 15px; font-weight: 700; line-height: 1.5; }
.consent-guidance { margin: 10px 0 0; color: var(--ink-strong); font-size: 13px; line-height: 1.65; font-weight: 600; overflow-wrap: anywhere; }
.upload-consent { box-sizing: border-box; width: 100%; min-height: 44px; height: auto; align-items: flex-start; white-space: normal; margin: 12px 0 0; padding: 12px; border: 1px solid var(--line-strong); border-radius: var(--radius-sm); background: var(--surface-panel); }
.upload-consent:focus-within { outline: 2px solid var(--accent); outline-offset: 3px; }
.upload-consent :deep(.el-checkbox__label) { min-width: 0; white-space: normal; overflow-wrap: anywhere; line-height: 1.65; font-size: 14px; color: var(--ink-strong); padding-left: 10px; }
.upload-consent :deep(.el-checkbox__input) { flex-shrink: 0; margin-top: 2px; }
.upload-consent :deep(.el-checkbox__inner) { width: 20px; height: 20px; border-width: 2px; }
.upload-consent :deep(.el-checkbox__inner::after) { left: 6px; top: 2px; }
.upload-consent :deep(.el-checkbox__input:not(.is-checked):not(.is-disabled) .el-checkbox__inner) { border-color: var(--warning); background: var(--surface-elevated); }
.analysis-section { margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--line-subtle); } .observation { margin-top: 12px; }
.observation > div { display: flex; gap: 8px; align-items: center; } .observation small { color: var(--ink-muted); }
.mode-descriptions { padding: 10px 12px; background: var(--accent-soft); border-radius: var(--radius-sm); font-size: 13px; }
.field { display: grid; gap: 7px; margin-top: 16px; } .field > span { color: var(--ink-strong); font-size: 13px; }
.output-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; min-width: 0; } .primary-action { width: 100%; margin-top: 18px; }
.creative-supplement, .media-track, .material-manifest { margin-top: 18px; padding: 14px; min-width: 0; border: 1px solid var(--line-strong); border-radius: var(--radius-md); background: var(--surface-panel); }
.creative-supplement h3:focus-visible { outline: 2px solid var(--accent); outline-offset: 4px; }
.media-track h3 { font-size: 15px; } .material-manifest article { margin: 12px 0; padding-top: 10px; border-top: 1px solid var(--line-subtle); overflow-wrap: anywhere; }
.plan-ready-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; justify-content: space-between; padding: 12px 16px; color: var(--ink-strong); border: 1px solid var(--accent); border-radius: var(--radius-sm); background: var(--accent-soft); }
.pane-plan-status { display: block; margin-top: 4px; font-size: 11px; } .mobile-panes :deep(.el-button > span) { display: flex; flex-direction: column; }
.prompt-text { min-height: 220px; max-height: 660px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.9; color: var(--ink-strong); }
.result-actions { justify-content: flex-start; flex-wrap: wrap; margin-top: 14px; } .result-actions :deep(.el-button + .el-button) { margin-left: 0; }
.result-warnings { padding-left: 18px; color: var(--ink-muted); font-size: 12px; } .history-row { display: flex; justify-content: space-between; gap: 16px; padding: 16px 0; border-bottom: 1px solid var(--line-subtle); }
.history-row > div { min-width: 0; } .history-row span { margin-left: 8px; font-size: 12px; } .history-row p { white-space: pre-wrap; overflow-wrap: anywhere; } .mobile-panes { display: none; }
@media (max-width: 1100px) and (min-width: 901px) { .image-workbench { padding: 16px; } .image-grid { grid-template-columns: minmax(210px, 0.8fr) minmax(250px, 1fr) minmax(250px, 1fr); gap: 10px; } .image-column { padding: 12px; } }
@media (max-width: 900px) { .image-grid { grid-template-columns: minmax(0, 1fr); } .mobile-panes { display: flex; gap: 8px; } .mobile-panes .el-button { flex: 1; margin-left: 0; min-height: 42px; height: auto; padding: 8px; } .image-grid[data-pane='images'] [data-column]:not([data-column='images']), .image-grid[data-pane='settings'] [data-column]:not([data-column='settings']), .image-grid[data-pane='result'] [data-column]:not([data-column='result']) { display: none; } }
@media (max-width: 640px) { .image-workbench { padding: 12px; } .image-heading { align-items: flex-start; flex-direction: column; } .image-column { padding: 14px; } .history-row { flex-direction: column; } }
</style>
