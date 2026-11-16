import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import type { DirectoryHandleLike } from '@/features/project-index/fileSystemDirectorySource';
import { createDirectorySelection } from '@/features/project-index/projectDirectorySelection';
import { useProjectIndex, type ProjectIndexStartOptions } from './useProjectIndex';

const repository = vi.hoisted(() => ({
  prepareProject: vi.fn(), saveProjectSource: vi.fn(), deleteProject: vi.fn(),
  observePreparation: vi.fn(() => vi.fn()),
}));
vi.mock('@/features/project-index/indexedDbProjectIndexRepository', () => ({ projectIndexRepository: repository }));

const options: ProjectIndexStartOptions = {
  retention: 'SESSION', autoCleanupDays: 1,
  limits: { maxScanFiles: 1000, maxIndexBytes: 1024 * 1024, maxIndexableFileBytes: 1024 },
};

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
};

const directory = (name: string) => ({
  kind: 'directory' as const, name, async *values() {},
  queryPermission: vi.fn<NonNullable<DirectoryHandleLike['queryPermission']>>().mockResolvedValue('prompt'),
  requestPermission: vi.fn<NonNullable<DirectoryHandleLike['requestPermission']>>().mockResolvedValue('granted'),
});

/** 先通过公开入口形成可重试任务，避免测试直接修改 composable 的内部状态。 */
const createInterruptedIndex = async () => {
  const first = directory('first'), second = directory('second');
  const source = createDirectorySelection([first, second]);
  const worker = {
    onmessage: null as ((event: MessageEvent<{ type: 'error'; message: string }>) => void) | null,
    onerror: null, terminate: vi.fn(), postMessage: vi.fn(),
  };
  worker.postMessage.mockImplementation(() => queueMicrotask(() => worker.onmessage?.({
    data: { type: 'error', message: '模拟索引中断' },
  } as MessageEvent<{ type: 'error'; message: string }>)));
  const constructor = vi.fn(function () { return worker; });
  vi.stubGlobal('Worker', constructor);
  const scope = effectScope(), index = scope.run(useProjectIndex)!;
  await expect(index.indexDirectory(options, source)).rejects.toThrow('模拟索引中断');
  expect(index.canRetry.value).toBe(true);
  return { first, second, source, constructor, worker, scope, index };
};

describe('多目录恢复授权', () => {
  beforeEach(() => {
    repository.prepareProject.mockResolvedValue(undefined);
    repository.saveProjectSource.mockResolvedValue(undefined);
    repository.deleteProject.mockResolvedValue(undefined);
    vi.stubGlobal('window', { showDirectoryPicker: vi.fn(),
      sessionStorage: { getItem: () => null, setItem: vi.fn() } });
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

  it('取消等待中的权限查询后，不弹出当前根或后续根的授权请求', async () => {
    const task = await createInterruptedIndex();
    const permission = deferred<PermissionState>();
    task.first.queryPermission.mockReturnValue(permission.promise);
    const pending = task.index.resumeIndexing(options);
    try {
      await vi.waitFor(() => expect(task.first.queryPermission).toHaveBeenCalledOnce());
      await task.index.cancelIndexing();
      permission.resolve('prompt');
      await expect(pending).resolves.toBeUndefined();
      expect(task.first.requestPermission).not.toHaveBeenCalled();
      expect(task.second.queryPermission).not.toHaveBeenCalled();
      expect(task.constructor).toHaveBeenCalledOnce();
    } finally { task.scope.stop(); permission.resolve('prompt'); await pending; }
  });

  it.each(['取消', '离开页面'] as const)('%s 后丢弃迟到的授权结果，不向下一根继续请求', async (action) => {
    const task = await createInterruptedIndex();
    const permission = deferred<PermissionState>();
    task.first.requestPermission.mockReturnValue(permission.promise);
    const pending = task.index.refreshIndex(options);
    try {
      await vi.waitFor(() => expect(task.first.requestPermission).toHaveBeenCalledOnce());
      if (action === '取消') await task.index.cancelIndexing();
      else task.scope.stop();
      permission.resolve('granted');
      await expect(pending).resolves.toBeUndefined();
      expect(task.second.queryPermission).not.toHaveBeenCalled();
      expect(task.second.requestPermission).not.toHaveBeenCalled();
      expect(task.constructor).toHaveBeenCalledOnce();
      expect(task.index.isSelecting.value).toBe(false);
    } finally { task.scope.stop(); permission.resolve('granted'); await pending; }
  });

  it('用户拒绝一个目录授权时停止，不查询其他目录或启动新 Worker', async () => {
    const task = await createInterruptedIndex();
    task.first.requestPermission.mockResolvedValue('denied');
    try {
      await expect(task.index.resumeIndexing(options)).rejects.toThrow('项目目录读取权限已失效');
      expect(task.second.queryPermission).not.toHaveBeenCalled();
      expect(task.constructor).toHaveBeenCalledOnce();
      expect(task.index.isSelecting.value).toBe(false);
      expect(repository.deleteProject).not.toHaveBeenCalled();
    } finally { task.scope.stop(); }
  });

  it('活动任务的权限接口异常正常报告，且不继续请求其他目录', async () => {
    const task = await createInterruptedIndex();
    task.first.queryPermission.mockRejectedValue(new Error('目录授权查询失败'));
    try {
      await expect(task.index.resumeIndexing(options)).rejects.toThrow('目录授权查询失败');
      expect(task.first.requestPermission).not.toHaveBeenCalled();
      expect(task.second.queryPermission).not.toHaveBeenCalled();
      expect(task.constructor).toHaveBeenCalledOnce();
    } finally { task.scope.stop(); }
  });

  it('取消后迟到的权限异常不重新报错或修改新状态', async () => {
    const task = await createInterruptedIndex();
    const permission = deferred<PermissionState>();
    task.first.requestPermission.mockReturnValue(permission.promise);
    const pending = task.index.resumeIndexing(options);
    try {
      await vi.waitFor(() => expect(task.first.requestPermission).toHaveBeenCalledOnce());
      await task.index.cancelIndexing();
      permission.reject(new Error('已取消的权限请求失败'));
      await expect(pending).resolves.toBeUndefined();
      expect(task.index.errorMessage.value).toBe('');
      expect(task.second.queryPermission).not.toHaveBeenCalled();
      expect(task.constructor).toHaveBeenCalledOnce();
    } finally { task.scope.stop(); permission.resolve('granted'); await pending.catch(() => undefined); }
  });

  it('所有根授权通过后仍使用原始来源启动恢复，不跳过必要授权', async () => {
    const task = await createInterruptedIndex();
    task.first.queryPermission.mockResolvedValue('granted');
    try {
      await expect(task.index.resumeIndexing(options)).rejects.toThrow('模拟索引中断');
      expect(task.first.requestPermission).not.toHaveBeenCalled();
      expect(task.second.requestPermission).toHaveBeenCalledOnce();
      expect(task.constructor).toHaveBeenCalledTimes(2);
      expect(task.worker.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({ rootHandle: task.source, mode: 'RESUME' }));
    } finally { task.scope.stop(); }
  });
});
