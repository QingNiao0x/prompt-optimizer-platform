import {
  readProjectFiles,
  type FileProcessingProgress,
  type ProjectFileSelection,
} from './fileReaderCore';
import { withRelativePath } from '@/composables/fileDrop';

interface WorkerRequest {
  files: File[];
  paths: string[];
}

type WorkerResponse =
  | { type: 'progress'; progress: FileProcessingProgress }
  | { type: 'done'; files: ProjectFileSelection['files']; warnings: string[] }
  | { type: 'error'; message: string };

const scope = self as unknown as {
  onmessage: ((event: MessageEvent<WorkerRequest>) => void) | null;
  postMessage: (message: WorkerResponse) => void;
};

/**
 * 在独立线程中读取并解析用户选择的文件，主线程只接收进度和最终结果，
 * 避免大型项目目录的扫描阻塞页面渲染。
 */
scope.onmessage = async (event: MessageEvent<WorkerRequest>): Promise<void> => {
  try {
    const { files, paths } = event.data;
    if (paths.length !== files.length) throw new Error('文件与路径数量不一致，请重新选择。');
    // 恢复主线程已经确认的目录前缀；解析核心仍逐项执行路径与敏感内容校验。
    const sources = files.map((file, index) => withRelativePath(file, paths[index]!));
    const selection = await readProjectFiles(sources, (progress) => {
      scope.postMessage({ type: 'progress', progress });
    });
    scope.postMessage({ type: 'done', files: selection.files, warnings: selection.warnings });
  } catch (error) {
    scope.postMessage({
      type: 'error',
      message: error instanceof Error ? error.message : '文件读取失败',
    });
  }
};
