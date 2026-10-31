import { afterEach, describe, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import type { DirectoryHandleLike } from '@/features/project-index/fileSystemDirectorySource';
import { useProjectIndex, type ProjectIndexStartOptions } from './useProjectIndex';

const repository = vi.hoisted(() => ({
  cleanupProjects: vi.fn(), saveProjectSource: vi.fn(),
  deleteProject: vi.fn(),
  observePreparation: vi.fn(() => vi.fn()),
}));
vi.mock('@/features/project-index/indexedDbProjectIndexRepository', () => ({ projectIndexRepository: repository }));

const options: ProjectIndexStartOptions = {
  retention: 'SESSION', autoCleanupDays: 1,
  limits: { maxScanFiles: 1000, maxIndexBytes: 1024 * 1024, maxIndexableFileBytes: 1024 },
};

describe('本地索引准备状态', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

  it('旧库准备未返回时不冒充目录已开始统计，页面离开后也不启动晚到任务', async () => {
    let release!: () => void;
    repository.cleanupProjects.mockReturnValue(new Promise<void>(resolve => { release = resolve; }));
    const root: DirectoryHandleLike = { kind: 'directory', name: 'sample', async *values() {} };
    vi.stubGlobal('window', { showDirectoryPicker: vi.fn(async () => root),
      sessionStorage: { getItem: () => null, setItem: vi.fn() } });
    const worker = vi.fn(); vi.stubGlobal('Worker', worker);
    const scope = effectScope();
    const index = scope.run(useProjectIndex)!;
    const pending = index.indexDirectory(options);
    try {
      await vi.waitFor(() => expect(repository.cleanupProjects).toHaveBeenCalledOnce());
      expect(index.isIndexing.value).toBe(true);
      expect(index.progress.value?.phase).toBe('PREPARING');
      expect(index.progress.value?.percent).toBeUndefined();
      expect(worker).not.toHaveBeenCalled();
    } finally {
      scope.stop(); release(); await pending;
    }
    expect(repository.saveProjectSource).not.toHaveBeenCalled();
    expect(worker).not.toHaveBeenCalled();
    expect(index.isIndexing.value).toBe(false);
  });

  it('新任务在旧库准备期间可立即取消，不排队删除旧项目，晚到准备也不会保存目录', async () => {
    let release!: () => void;
    repository.cleanupProjects.mockReturnValue(new Promise<void>(resolve => { release = resolve; }));
    const root: DirectoryHandleLike = { kind: 'directory', name: 'sample', async *values() {} };
    vi.stubGlobal('window', { showDirectoryPicker: vi.fn(async () => root),
      sessionStorage: { getItem: () => null, setItem: vi.fn() } });
    const scope = effectScope(), index = scope.run(useProjectIndex)!;
    const pending = index.indexDirectory(options);
    try {
      await vi.waitFor(() => expect(repository.cleanupProjects).toHaveBeenCalledOnce());
      await index.cancelIndexing();
      expect(index.isIndexing.value).toBe(false);
      expect(index.progress.value).toBeUndefined();
      expect(repository.deleteProject).not.toHaveBeenCalled();
      release(); await pending;
      expect(repository.saveProjectSource).not.toHaveBeenCalled();
    } finally { scope.stop(); release(); await pending; }
  });
});
