import {
  readProjectFiles,
  type FileProcessingProgress,
  type ProjectFileSelection,
} from './fileReaderCore';

interface WorkerRequest {
  files: File[];
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
    const selection = await readProjectFiles(event.data.files, (progress) => {
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
