/** 新导入按项目隔离存储；路由写入 ID，Worker、重试和检索无需读取可能正被占用的旧目录表。 */
export interface ProjectStorageBucket {
  indexedDB: IDBFactory;
  setExpires(time: number): Promise<void>;
  persist(): Promise<boolean>;
}

export interface ProjectStorageBucketManager {
  open(name: string, options?: { expires?: number; durability?: 'strict' }): Promise<ProjectStorageBucket>;
  keys(): Promise<string[]>;
  delete(name: string): Promise<void>;
}

export interface ProjectIndexDatabaseLocation {
  name: string;
  factory: () => Promise<IDBFactory>;
}

const ISOLATED_ID = /^pi-([bd])1-[a-z0-9-]+$/;
const DATABASE_PREFIX = 'prompt-optimizer-project-';

export const storageBuckets = (): ProjectStorageBucketManager | undefined =>
  (globalThis.navigator as (Navigator & { storageBuckets?: ProjectStorageBucketManager }) | undefined)?.storageBuckets;

/** 只在创建时选择兼容路径；已写入独立桶的项目不能在 API 不可用时偷偷落回另一个空库。 */
export const createIsolatedProjectId = (): string => {
  const key = globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  return `pi-${storageBuckets() ? 'b' : 'd'}1-${key}`;
};

export const isIsolatedProject = (projectId: string): boolean => ISOLATED_ID.test(projectId);

export const projectDatabaseLocation = (projectId: string): ProjectIndexDatabaseLocation | undefined => {
  const match = ISOLATED_ID.exec(projectId);
  if (!match) return undefined;
  return {
    name: `${DATABASE_PREFIX}${projectId}`,
    factory: async () => {
      if (match[1] === 'd') return indexedDB;
      const manager = storageBuckets();
      if (!manager) throw new Error('当前浏览器不支持此项目的独立存储，请在原浏览器继续索引');
      return (await waitForIndexStorage(manager.open(projectId))).indexedDB;
    },
  };
};

/** 沿用用户设置的保留期限；刷新时显式续期，普通读取不会延长源码保留期。 */
export const prepareIsolatedStorage = async (
  projectId: string, expiresAt: string, retention: 'SESSION' | 'PERSISTENT',
): Promise<void> => {
  if (!projectId.startsWith('pi-b1-') || !isIsolatedProject(projectId)) return;
  const manager = storageBuckets();
  if (!manager) throw new Error('当前浏览器不支持此项目的独立存储，请在原浏览器继续索引');
  const expires = Date.parse(expiresAt);
  if (!Number.isFinite(expires) || expires <= Date.now()) throw new Error('项目索引保留期限已失效，请重新选择目录');
  const bucket = await waitForIndexStorage(manager.open(projectId, { expires, durability: 'strict' }));
  await waitForIndexStorage(bucket.setExpires(expires));
  if (retention === 'PERSISTENT') await waitForIndexStorage(bucket.persist());
};

export const deleteIsolatedStorage = async (projectId: string): Promise<void> => {
  const location = projectDatabaseLocation(projectId);
  if (!location) return;
  if (projectId.startsWith('pi-b1-')) {
    const manager = storageBuckets();
    if (!manager) throw new Error('当前浏览器无法清理此项目的独立存储');
    await waitForIndexStorage(manager.delete(projectId));
  } else {
    await waitForIndexStorage(new Promise<void>((resolve, reject) => {
      const request = indexedDB.deleteDatabase(location.name);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error('其他页面正在使用该项目索引，请关闭对应页面后重试'));
    }));
  }
};

/** 设置页显式清理仅枚举本功能命名空间，不清除登录、Plan 草稿或其他本地资料。 */
export const listIsolatedProjects = async (kind: 'bucket' | 'database'): Promise<string[]> => {
  if (kind === 'bucket') {
    const manager = storageBuckets();
    return manager ? (await waitForIndexStorage(manager.keys())).filter(id => /^pi-b1-/.test(id) && isIsolatedProject(id)) : [];
  }
  const databases = typeof indexedDB.databases === 'function' ? await waitForIndexStorage(indexedDB.databases()) : [];
  return databases.map(db => db.name?.startsWith(DATABASE_PREFIX) ? db.name.slice(DATABASE_PREFIX.length) : '')
    .filter(id => /^pi-d1-/.test(id) && isIsolatedProject(id));
};

/** 浏览器存储服务可能长期排队；超时必须明确失败可重试，不能伪造成功或无限转圈。 */
export const waitForIndexStorage = <T>(pending: Promise<T>, timeoutMs = 20_000): Promise<T> =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('浏览器本地存储长时间未响应，请刷新本页面后继续索引；已有项目数据仍保留')), timeoutMs);
    pending.then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
  });
