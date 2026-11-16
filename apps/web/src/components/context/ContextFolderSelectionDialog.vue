<script setup lang="ts">
import { ElButton, ElDialog, ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue';
import { pickProjectDirectory, type DirectoryHandleLike } from '@/features/project-index/fileSystemDirectorySource';
import { createDirectorySelection, uniqueDirectoryPrefix, type ProjectDirectorySource } from '@/features/project-index/projectDirectorySelection';
import { withRelativePath } from '@/composables/fileDrop';

const props = defineProps<{ nativePicker: boolean }>();
const emit = defineEmits<{
  (event: 'directories-selected', source: ProjectDirectorySource): void;
  (event: 'files-selected', files: File[]): void;
}>();
type FolderChoice = { name: string; handle: DirectoryHandleLike } | { name: string; files: File[] };
const visible = ref(false), choosing = ref(false);
const choices = shallowRef<FolderChoice[]>([]);
const folderInput = ref<HTMLInputElement>();
let selectionVersion = 0;
const labels = computed(() => {
  const result: string[] = [];
  for (const choice of choices.value) result.push(uniqueDirectoryPrefix(choice.name, result));
  return result;
});

/** 原生目录选择器一次只授权一个目录；清单累积多选后一次提交，取消时不开始读取或索引。 */
const addFolder = async (): Promise<void> => {
  if (choosing.value) return;
  choosing.value = true;
  const version = selectionVersion;
  if (!props.nativePicker) { folderInput.value?.click(); return; }
  try {
    const handle = await pickProjectDirectory();
    if (!visible.value || version !== selectionVersion) return;
    for (const choice of choices.value) {
      const prior = 'handle' in choice ? choice.handle : undefined;
      if (prior && await handle.isSameEntry?.(prior)) {
        ElMessage.info('该文件夹已在选择清单中。');
        return;
      }
    }
    if (visible.value && version === selectionVersion) choices.value = [...choices.value, { name: handle.name, handle }];
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) ElMessage.error('未能选择文件夹，请检查目录权限后重试。');
  } finally { if (version === selectionVersion) choosing.value = false; }
};

const acceptFallback = (event: Event): void => {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = '';
  if (!visible.value) return;
  choosing.value = false;
  const groups = new Map<string, File[]>();
  for (const file of files) {
    const name = file.webkitRelativePath.split('/')[0] || '所选文件夹';
    const group = groups.get(name) ?? [];
    group.push(file); groups.set(name, group);
  }
  choices.value = [...choices.value, ...Array.from(groups, ([name, group]) => ({ name, files: group }))];
};

const close = (): void => {
  selectionVersion += 1;
  visible.value = false;
  choosing.value = false;
  choices.value = [];
};

const confirm = (): void => {
  if (choosing.value || !choices.value.length) return;
  const handles = choices.value.filter((item): item is { name: string; handle: DirectoryHandleLike } => 'handle' in item);
  if (handles.length && handles.length !== choices.value.length) {
    ElMessage.error('目录选择方式发生变化，请取消后重新选择，避免遗漏文件。');
    return;
  }
  if (handles.length === choices.value.length) emit('directories-selected', createDirectorySelection(handles.map(item => item.handle)));
  else emit('files-selected', choices.value.flatMap((choice, index) => 'files' in choice
    ? choice.files.map(file => withRelativePath(file, `${labels.value[index]}/${file.webkitRelativePath.split('/').slice(1).join('/') || file.name}`)) : []));
  close();
};

const open = (): void => { close(); visible.value = true; void addFolder(); };
/** 拖入多个原生目录也使用完整索引入口，避免降级为有数量限制的代码片段导入。 */
const openDirectories = (handles: DirectoryHandleLike[]): void => {
  close(); visible.value = true; choices.value = handles.map(handle => ({ name: handle.name, handle }));
};
onBeforeUnmount(close);
defineExpose({ open, openDirectories });
</script>

<template>
  <!-- 保持输入挂载，首个点击事件内才能同步打开原生选择器；ElDialog 的正文会延迟渲染。 -->
  <input ref="folderInput" data-testid="folder-selection-input" class="visually-hidden" type="file" multiple webkitdirectory
    @change="acceptFallback" @cancel="choosing = false" />
  <ElDialog :model-value="visible" title="选择文件夹" width="min(620px, 94vw)" append-to-body
    :close-on-click-modal="false" @update:model-value="value => { if (!value) close(); }">
    <p class="field-help">可连续添加多个文件夹，确认后一起处理。浏览器的目录窗口每次选择一个；也可一次拖入多个文件夹。</p>
    <div class="folder-selection-list" aria-label="已选文件夹">
      <p v-if="!choices.length" class="field-help">尚未选择文件夹。</p>
      <div v-for="(_, index) in choices" :key="index" class="folder-selection-item">
        <span :title="labels[index]">{{ labels[index] }}</span>
        <ElButton text type="danger" :disabled="choosing" :aria-label="`移除 ${labels[index]}`"
          @click="choices = choices.filter((_, itemIndex) => itemIndex !== index)">移除</ElButton>
      </div>
    </div>
    <p v-if="choices.length > 1" class="field-help">文件路径保留各文件夹前缀；同名文件夹会自动编号，避免相互覆盖。</p>
    <template #footer>
      <div class="folder-selection-actions">
        <ElButton :loading="choosing" @click="addFolder">继续添加文件夹</ElButton>
        <ElButton @click="close">取消</ElButton>
        <ElButton type="primary" :disabled="!choices.length || choosing" @click="confirm">开始处理 {{ choices.length }} 个文件夹</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<style scoped>
.folder-selection-list { max-height: min(42vh, 340px); overflow: auto; border: 1px solid var(--glass-border); border-radius: var(--radius-sm); padding: 8px 12px; }
.folder-selection-item { display: flex; align-items: center; gap: 12px; min-width: 0; }
.folder-selection-item span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.folder-selection-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.folder-selection-actions :deep(.el-button) { margin-left: 0; }
</style>
