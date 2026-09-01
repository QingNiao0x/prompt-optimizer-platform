import { nextTick, readonly, ref } from 'vue';

import type { ContextFileInput } from '@/types/api';
import {
  collectCandidateFiles,
  MAX_FILES,
  readProjectFiles,
  type FileProcessingProgress,
  type ProjectFileSelection,
} from '@/workers/fileReaderCore';

export {
  MAX_FILES,
  UNSUPPORTED_EXTENSIONS,
  readProjectFiles,
} from '@/workers/fileReaderCore';
export type { FileProcessingProgress, ProjectFileSelection } from '@/workers/fileReaderCore';

type WorkerResponse =
  | { type: 'progress'; progress: FileProcessingProgress }
  | { type: 'done'; files: ProjectFileSelection['files']; warnings: string[] }
  | { type: 'error'; message: string };

interface WorkerRequest {
  files: File[];
}

const readFilesInWorker = (
  files: File[],
  onProgress: (progress: FileProcessingProgress) => void,
): Promise<ProjectFileSelection> =>
  new Promise((resolve, reject) => {
    const worker = new Worker(
      new URL('../workers/fileReader.worker.ts', import.meta.url),
      { type: 'module' },
    );
    worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
      const message = event.data;
      if (message.type === 'progress') {
        onProgress(message.progress);
        return;
      }
      worker.terminate();
      if (message.type === 'done') {
        resolve({ files: message.files, warnings: message.warnings });
      } else {
        reject(new Error(message.message));
      }
    };
    worker.onerror = (event) => {
      worker.terminate();
      reject(new Error(event.message || '文件读取线程启动失败'));
    };
    worker.postMessage({ files } satisfies WorkerRequest);
  });

export const useProjectFiles = () => {
  const isReading = ref(false);
  const warnings = ref<string[]>([]);
  const progress = ref<FileProcessingProgress | null>(null);

  // 让出主线程，给浏览器一次重绘机会，避免界面在大量文件时看起来没有响应。
  const yieldToBrowser = (): Promise<void> =>
    new Promise((resolve) => setTimeout(resolve, 0));

  const selectFileArray = async (fileArray: readonly File[]): Promise<ContextFileInput[]> => {
    if (fileArray.length === 0) {
      warnings.value = [];
      progress.value = null;
      return [];
    }

    isReading.value = true;
    progress.value = {
      current: 0,
      total: fileArray.length,
      fileName: '',
      percent: 0,
      accepted: 0,
      skipped: 0,
      phase: 'scan',
    };
    if (import.meta.env.DEV) {
      console.info('[file-upload] start', {
        total: fileArray.length,
        at: new Date().toISOString(),
      });
    }

    try {
      // 第一阶段只扫描文件名和大小，绝不全量复制或克隆十六万文件。
      // 扫描分片执行，每 500 条让出一次主线程；FileList 由 ContextPanel 延迟清空，保证扫描期间有效。
      const scanWarnings: string[] = [];
      const scanStartedAt = performance.now();
      const { files: candidates, stats } = await collectCandidateFiles(
        fileArray,
        MAX_FILES,
        async (scanned, total, selected) => {
          progress.value = {
            current: scanned,
            total,
            fileName: '',
            percent: total === 0 ? 100 : Math.round((scanned / total) * 100),
            accepted: selected,
            skipped: 0,
            phase: 'scan',
          };
          await yieldToBrowser();
        },
      );
      if (import.meta.env.DEV) {
        console.info('[file-upload] scan finished', {
          fileListLength: fileArray.length,
          scanned: stats.total,
           selected: stats.selected,
           pathIgnored: stats.pathIgnored,
           sensitive: stats.sensitive,
          unsupported: stats.unsupported,
          oversized: stats.oversized,
          tookMs: Math.round(performance.now() - scanStartedAt),
        });
      }
      progress.value = {
        current: stats.total,
        total: stats.total,
        fileName: '',
        percent: stats.total === 0 ? 100 : 100,
        accepted: stats.selected,
         skipped: stats.pathIgnored + stats.sensitive + stats.unsupported + stats.oversized,
        phase: 'scan',
      };

      // 先渲染出进度条容器，再开始读取。
      await nextTick();
      await yieldToBrowser();

      if (stats.pathIgnored > 0) {
        scanWarnings.push(`已跳过 ${stats.pathIgnored} 个依赖目录、构建产物或扫描范围外的文件。`);
      }
      if (stats.sensitive > 0) {
        scanWarnings.push(`已跳过 ${stats.sensitive} 个敏感文件（密钥、私钥、.env 或凭据文件），不会上传。`);
      }
      if (stats.unsupported > 0) {
        scanWarnings.push(`已跳过 ${stats.unsupported} 个暂不支持的文件。`);
      }
      if (stats.oversized > 0) {
        scanWarnings.push(`已跳过 ${stats.oversized} 个超过大小限制的文件。`);
      }
      if (candidates.length === 0) {
        scanWarnings.push('没有找到可读取的文件。');
        warnings.value = scanWarnings;
        return [];
      }

      // 第二阶段只把候选文件发给 Worker，主线程保持响应。
      const readStartedAt = performance.now();
      progress.value = {
        current: 0,
        total: candidates.length,
        fileName: '',
        percent: 0,
        accepted: 0,
        skipped: 0,
        phase: 'read',
      };
      await yieldToBrowser();

      let selection: ProjectFileSelection;
      try {
        // 优先在 Web Worker 中解析，主线程保持响应；Worker 不可用时回退到主线程时间切片。
        selection = await readFilesInWorker(candidates, (next) => {
          progress.value = next;
        });
      } catch (workerError) {
        if (import.meta.env.DEV) {
          console.info('[file-reader] worker unavailable, fallback to main thread', workerError);
        }
        selection = await readProjectFiles(candidates, async (next) => {
          progress.value = next;
          await yieldToBrowser();
        });
      }
      if (import.meta.env.DEV) {
        console.info('[file-upload] read finished', {
          files: selection.files.length,
          tookMs: Math.round(performance.now() - readStartedAt),
        });
      }
      warnings.value = [...scanWarnings, ...selection.warnings];
      return selection.files;
    } finally {
      isReading.value = false;
      progress.value = null;
    }
  };

  const selectFiles = async (fileList: FileList | null): Promise<ContextFileInput[]> =>
    selectFileArray(fileList ? Array.from(fileList) : []);

  return {
    isReading: readonly(isReading),
    warnings: readonly(warnings),
    progress: readonly(progress),
    selectFiles,
    selectFileArray,
  };
};
