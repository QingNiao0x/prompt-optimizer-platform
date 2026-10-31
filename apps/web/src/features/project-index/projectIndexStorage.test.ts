import { afterEach, describe, expect, it, vi } from 'vitest';
import { createIsolatedProjectId, deleteIsolatedStorage, prepareIsolatedStorage,
  projectDatabaseLocation, waitForIndexStorage } from './projectIndexStorage';
import { IndexedDbProjectIndexRepository, RoutedProjectIndexRepository } from './indexedDbProjectIndexRepository';

describe('项目存储隔离与兼容', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers(); });

  it('支持桶时路由固定，不访问默认库；保留期限和持久化设置落实到同一桶', async () => {
    const factory = {} as IDBFactory;
    const bucket = { indexedDB: factory, setExpires: vi.fn(async () => {}), persist: vi.fn(async () => true) };
    const manager = { open: vi.fn(async () => bucket), delete: vi.fn(async () => {}), keys: vi.fn(async () => []) };
    vi.stubGlobal('navigator', { storageBuckets: manager });
    vi.stubGlobal('indexedDB', { open: vi.fn(() => { throw new Error('不应访问默认库'); }) });
    const id = createIsolatedProjectId(), expires = new Date(Date.now() + 60_000).toISOString();
    expect(id).toMatch(/^pi-b1-/);
    await prepareIsolatedStorage(id, expires, 'PERSISTENT');
    expect(bucket.setExpires).toHaveBeenCalledWith(Date.parse(expires));
    expect(bucket.persist).toHaveBeenCalledOnce();
    expect(await projectDatabaseLocation(id)!.factory()).toBe(factory);
    await deleteIsolatedStorage(id);
    expect(manager.delete).toHaveBeenCalledWith(id);
  });

  it('不支持桶时使用按项目命名的数据库；旧 ID 保持原路由', async () => {
    vi.stubGlobal('navigator', {});
    const factory = {} as IDBFactory;
    vi.stubGlobal('indexedDB', factory);
    const id = createIsolatedProjectId();
    expect(id).toMatch(/^pi-d1-/);
    expect(projectDatabaseLocation('legacy-id')).toBeUndefined();
    expect(projectDatabaseLocation(id)!.name).toContain(id);
    expect(await projectDatabaseLocation(id)!.factory()).toBe(factory);
  });

  it('已经使用桶的项目不静默回退到默认空库', async () => {
    vi.stubGlobal('navigator', {});
    await expect(projectDatabaseLocation('pi-b1-existing')!.factory()).rejects.toThrow('原浏览器');
  });

  it('准备新目录不调用旧库清理，旧 ID 的继续操作保留原有维护', async () => {
    vi.stubGlobal('navigator', {});
    const cleanup = vi.spyOn(IndexedDbProjectIndexRepository.prototype, 'cleanupProjects').mockResolvedValue(0);
    const repository = new RoutedProjectIndexRepository();
    await repository.prepareProject('pi-d1-new', new Date(Date.now() + 60_000).toISOString(), 'SESSION');
    expect(cleanup).not.toHaveBeenCalled();
    await repository.prepareProject('old-project', new Date(Date.now() + 60_000).toISOString(), 'SESSION');
    expect(cleanup).toHaveBeenCalledOnce();
  });

  it('存储服务无响应时给出可恢复错误，晚到结果不能覆盖超时', async () => {
    vi.useFakeTimers();
    let release!: (value: string) => void;
    const pending = waitForIndexStorage(new Promise<string>(resolve => { release = resolve; }), 100);
    const assertion = expect(pending).rejects.toThrow('已有项目数据仍保留');
    await vi.advanceTimersByTimeAsync(100); await assertion;
    release('late'); await Promise.resolve();
  });

  it('一个写入批次不能跨项目，避免将另一目录内容写入当前桶', async () => {
    const repository = new RoutedProjectIndexRepository();
    const chunk = (projectId: string) => ({ id: `${projectId}\0x`, projectId, path: 'x', language: 'text',
      content: 'x', chunkIndex: 0, priority: 1, searchTerms: ['x'], symbols: [], imports: [] });
    await expect(repository.writeBatch([], [chunk('pi-b1-a'), chunk('pi-b1-b')])).rejects.toThrow('其他项目');
  });

  it('设置页清理同时覆盖新桶和旧库，且不清除其他功能的存储', async () => {
    const remove = vi.fn(async () => {});
    vi.stubGlobal('navigator', { storageBuckets: { keys: async () => ['pi-b1-owned', 'other-feature'], delete: remove } });
    vi.stubGlobal('indexedDB', { databases: async () => [{ name: 'other-database' }] });
    const legacy = vi.spyOn(IndexedDbProjectIndexRepository.prototype, 'deleteAllProjects').mockResolvedValue(undefined);
    await new RoutedProjectIndexRepository().deleteAllProjects();
    expect(remove).toHaveBeenCalledExactlyOnceWith('pi-b1-owned');
    expect(legacy).toHaveBeenCalledOnce();
  });

  it('旧存储服务失败时独立桶仍能清理，但必须报告没有全部清理成功', async () => {
    const remove = vi.fn(async () => {});
    vi.stubGlobal('navigator', { storageBuckets: { keys: async () => ['pi-b1-owned'], delete: remove } });
    vi.stubGlobal('indexedDB', { databases: async () => { throw new Error('busy'); } });
    vi.spyOn(IndexedDbProjectIndexRepository.prototype, 'deleteAllProjects').mockRejectedValue(new Error('busy'));
    await expect(new RoutedProjectIndexRepository().deleteAllProjects()).rejects.toThrow('尚未清理完成');
    expect(remove).toHaveBeenCalledExactlyOnceWith('pi-b1-owned');
  });
});
