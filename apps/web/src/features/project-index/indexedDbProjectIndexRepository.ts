import type {
  IndexedProjectChunk,
  IndexedProjectFile,
  ProjectIndexRepository,
  ProjectIndexSummary,
} from './projectIndexer';
import type { DirectoryHandleLike } from './fileSystemDirectorySource';

const DATABASE_NAME = 'prompt-optimizer-project-index';
const DATABASE_VERSION = 2;
const PROJECT_STORE = 'projects';
const FILE_STORE = 'files';
const CHUNK_STORE = 'chunks';
const SOURCE_STORE = 'sources';

interface ProjectSourceRecord {
  projectId: string;
  rootHandle: DirectoryHandleLike;
}

/**
 * IndexedDB 仓库将大项目索引保留在浏览器本地，页面状态只保存索引编号和摘要。
 */
export class IndexedDbProjectIndexRepository implements ProjectIndexRepository {
  private databasePromise?: Promise<IDBDatabase>;
  private activeDatabase?: IDBDatabase;

  async beginProject(summary: ProjectIndexSummary): Promise<void> {
    await this.putProject(summary);
  }

  async findFiles(projectId: string, paths: string[]): Promise<IndexedProjectFile[]> {
    if (paths.length === 0) {
      return [];
    }
    const transaction = await this.createTransaction(FILE_STORE, 'readonly');
    const completion = transactionComplete(transaction);
    const index = transaction.objectStore(FILE_STORE).index('projectPath');
    const requests = paths.map((path) =>
      requestResult<IndexedProjectFile | undefined>(index.get([projectId, path])));
    const files = await Promise.all(requests);
    await completion;
    return files.filter((file): file is IndexedProjectFile => file !== undefined);
  }

  async writeBatch(
    files: IndexedProjectFile[],
    chunks: IndexedProjectChunk[],
    replacedPaths: string[] = [],
  ): Promise<void> {
    if (files.length === 0 && chunks.length === 0 && replacedPaths.length === 0) {
      return;
    }
    const transaction = await this.createTransaction([FILE_STORE, CHUNK_STORE], 'readwrite');
    const completion = transactionComplete(transaction);
    const fileStore = transaction.objectStore(FILE_STORE);
    const chunkStore = transaction.objectStore(CHUNK_STORE);
    const projectId = files[0]?.projectId ?? chunks[0]?.projectId;
    if (projectId) {
      for (const path of replacedPaths) {
        chunkStore.delete(chunkIdRange(projectId, path));
      }
    }
    for (const file of files) {
      fileStore.put(file);
    }
    for (const chunk of chunks) {
      chunkStore.put(chunk);
    }
    await completion;
  }

  async pauseProject(summary: ProjectIndexSummary): Promise<void> {
    await this.putProject(summary);
  }

  async completeProject(summary: ProjectIndexSummary): Promise<void> {
    await this.putProject(summary);
  }

  async removeUnseenFiles(projectId: string, scanId: string): Promise<number> {
    const transaction = await this.createTransaction([FILE_STORE, CHUNK_STORE], 'readwrite');
    const completion = transactionComplete(transaction);
    const fileStore = transaction.objectStore(FILE_STORE);
    const chunkStore = transaction.objectStore(CHUNK_STORE);
    const request = fileStore.index('projectId').openCursor(IDBKeyRange.only(projectId));
    let removedFiles = 0;
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        return;
      }
      const file = cursor.value as IndexedProjectFile;
      if (file.lastSeenScanId !== scanId) {
        cursor.delete();
        chunkStore.delete(chunkIdRange(projectId, file.path));
        removedFiles += 1;
      }
      cursor.continue();
    };
    request.onerror = () => transaction.abort();
    await completion;
    return removedFiles;
  }

  async failProject(projectId: string, message: string): Promise<void> {
    const summary = await this.findProject(projectId);
    if (!summary) {
      return;
    }
    await this.putProject({ ...summary, status: 'FAILED', errorMessage: message });
  }

  async findCandidateChunks(
    projectId: string,
    searchTerms: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    const unique = new Map<string, IndexedProjectChunk>();
    const priorityChunks = await this.getChunksByPriority(projectId, Math.min(limit, 100));
    for (const chunk of priorityChunks) {
      unique.set(chunk.id, chunk);
    }

    for (const term of searchTerms.slice(0, 12)) {
      const matches = await this.getChunksBySearchTerm(`${projectId}:${term}`, 100);
      for (const chunk of matches) {
        unique.set(chunk.id, chunk);
        if (unique.size >= limit) {
          return Array.from(unique.values());
        }
      }
    }

    if (unique.size === 0) {
      const fallback = await this.getChunksByProject(projectId, limit);
      for (const chunk of fallback) {
        unique.set(chunk.id, chunk);
      }
    }
    return Array.from(unique.values()).slice(0, limit);
  }

  async findChunksByPaths(
    projectId: string,
    paths: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    if (paths.length === 0 || limit <= 0) {
      return [];
    }
    const transaction = await this.createTransaction(CHUNK_STORE, 'readonly');
    const completion = transactionComplete(transaction);
    const index = transaction.objectStore(CHUNK_STORE).index('projectPath');
    const requests = paths.map((path) =>
      requestResult<IndexedProjectChunk[]>(
        index.getAll(IDBKeyRange.only([projectId, path]), limit),
      ));
    const groups = await Promise.all(requests);
    await completion;
    return groups.flat().slice(0, limit).map(normalizeChunk);
  }

  async deleteProject(projectId: string): Promise<void> {
    await Promise.all([
      this.deleteRecordsByProject(FILE_STORE, projectId),
      this.deleteRecordsByProject(CHUNK_STORE, projectId),
    ]);
    const transaction = await this.createTransaction([PROJECT_STORE, SOURCE_STORE], 'readwrite');
    const completion = transactionComplete(transaction);
    transaction.objectStore(PROJECT_STORE).delete(projectId);
    transaction.objectStore(SOURCE_STORE).delete(projectId);
    await completion;
  }

  async saveProjectSource(projectId: string, rootHandle: DirectoryHandleLike): Promise<void> {
    const transaction = await this.createTransaction(SOURCE_STORE, 'readwrite');
    const completion = transactionComplete(transaction);
    transaction.objectStore(SOURCE_STORE).put({ projectId, rootHandle } satisfies ProjectSourceRecord);
    await completion;
  }

  async findProjectSource(projectId: string): Promise<DirectoryHandleLike | undefined> {
    const transaction = await this.createTransaction(SOURCE_STORE, 'readonly');
    const completion = transactionComplete(transaction);
    const source = await requestResult<ProjectSourceRecord | undefined>(
      transaction.objectStore(SOURCE_STORE).get(projectId),
    );
    await completion;
    return source?.rootHandle;
  }

  async findProject(projectId: string): Promise<ProjectIndexSummary | undefined> {
    const transaction = await this.createTransaction(PROJECT_STORE, 'readonly');
    const completion = transactionComplete(transaction);
    const result = await requestResult<ProjectIndexSummary | undefined>(
      transaction.objectStore(PROJECT_STORE).get(projectId),
    );
    await completion;
    return result ? normalizeProjectSummary(result) : undefined;
  }

  /**
   * 清理已过期或失败的索引。不能仅凭会话编号删除其他标签页的索引，避免误删仍在使用的数据。
   */
  async cleanupProjects(now = new Date()): Promise<number> {
    const projects = await this.listProjects();
    const expired = projects.filter((project) => {
      const expiresAt = Date.parse(project.expiresAt);
      const reachedExpiry = Number.isFinite(expiresAt) && expiresAt <= now.getTime();
      return reachedExpiry || project.status === 'FAILED';
    });
    for (const project of expired) {
      await this.deleteProject(project.id);
    }
    return expired.length;
  }

  async deleteAllProjects(): Promise<void> {
    const transaction = await this.createTransaction(
      [PROJECT_STORE, FILE_STORE, CHUNK_STORE, SOURCE_STORE],
      'readwrite',
    );
    const completion = transactionComplete(transaction);
    transaction.objectStore(PROJECT_STORE).clear();
    transaction.objectStore(FILE_STORE).clear();
    transaction.objectStore(CHUNK_STORE).clear();
    transaction.objectStore(SOURCE_STORE).clear();
    await completion;
  }

  private async putProject(summary: ProjectIndexSummary): Promise<void> {
    const transaction = await this.createTransaction(PROJECT_STORE, 'readwrite');
    const completion = transactionComplete(transaction);
    transaction.objectStore(PROJECT_STORE).put(summary);
    await completion;
  }

  private async listProjects(): Promise<ProjectIndexSummary[]> {
    const transaction = await this.createTransaction(PROJECT_STORE, 'readonly');
    const completion = transactionComplete(transaction);
    const result = await requestResult<ProjectIndexSummary[]>(
      transaction.objectStore(PROJECT_STORE).getAll(),
    );
    await completion;
    return result.map(normalizeProjectSummary);
  }

  private async getChunksByPriority(
    projectId: string,
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    const range = IDBKeyRange.bound([projectId, 0], [projectId, 9]);
    return this.getChunksFromIndex('projectPriority', range, limit);
  }

  private async getChunksBySearchTerm(
    searchTerm: string,
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    return this.getChunksFromIndex('searchTerms', searchTerm, limit);
  }

  private async getChunksByProject(
    projectId: string,
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    return this.getChunksFromIndex('projectId', projectId, limit);
  }

  private async getChunksFromIndex(
    indexName: string,
    query: IDBValidKey | IDBKeyRange,
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    const transaction = await this.createTransaction(CHUNK_STORE, 'readonly');
    const completion = transactionComplete(transaction);
    const result = await requestResult<IndexedProjectChunk[]>(
      transaction.objectStore(CHUNK_STORE).index(indexName).getAll(query, limit),
    );
    await completion;
    return result.map(normalizeChunk);
  }

  private async deleteRecordsByProject(storeName: string, projectId: string): Promise<void> {
    const transaction = await this.createTransaction(storeName, 'readwrite');
    const completion = transactionComplete(transaction);
    const request = transaction
      .objectStore(storeName)
      .index('projectId')
      .openKeyCursor(IDBKeyRange.only(projectId));
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        return;
      }
      transaction.objectStore(storeName).delete(cursor.primaryKey);
      cursor.continue();
    };
    request.onerror = () => transaction.abort();
    await completion;
  }

  /**
   * 创建事务时如果浏览器正在关闭旧连接，则清除缓存并自动重连一次。
   */
  private async createTransaction(
    storeNames: string | string[],
    mode: IDBTransactionMode,
  ): Promise<IDBTransaction> {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const database = await this.openDatabase();
      try {
        return database.transaction(storeNames, mode);
      } catch (error) {
        if (attempt > 0 || !isClosingConnectionError(error)) {
          throw error;
        }
        this.invalidateDatabase(database);
      }
    }
    throw new Error('无法创建本地项目索引事务');
  }

  private openDatabase(): Promise<IDBDatabase> {
    if (!this.databasePromise) {
      let openingPromise: Promise<IDBDatabase>;
      openingPromise = new Promise((resolve, reject) => {
        const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
        request.onupgradeneeded = () => {
          const database = request.result;
          if (!database.objectStoreNames.contains(PROJECT_STORE)) {
            database.createObjectStore(PROJECT_STORE, { keyPath: 'id' });
          }
          if (!database.objectStoreNames.contains(FILE_STORE)) {
            const fileStore = database.createObjectStore(FILE_STORE, { keyPath: 'id' });
            fileStore.createIndex('projectId', 'projectId');
            fileStore.createIndex('projectPath', ['projectId', 'path'], { unique: true });
          }
          if (!database.objectStoreNames.contains(CHUNK_STORE)) {
            const chunkStore = database.createObjectStore(CHUNK_STORE, { keyPath: 'id' });
            chunkStore.createIndex('projectId', 'projectId');
            chunkStore.createIndex('projectPath', ['projectId', 'path']);
            chunkStore.createIndex('projectPriority', ['projectId', 'priority']);
            chunkStore.createIndex('searchTerms', 'searchTerms', { multiEntry: true });
          }
          if (!database.objectStoreNames.contains(SOURCE_STORE)) {
            database.createObjectStore(SOURCE_STORE, { keyPath: 'projectId' });
          }
        };
        request.onsuccess = () => {
          const database = request.result;
          this.activeDatabase = database;
          database.onversionchange = () => this.invalidateDatabase(database);
          database.onclose = () => this.clearDatabaseCache(database, openingPromise);
          resolve(database);
        };
        request.onerror = () => {
          this.clearDatabaseCache(undefined, openingPromise);
          reject(request.error ?? new Error('无法打开本地项目索引'));
        };
        request.onblocked = () => {
          this.clearDatabaseCache(undefined, openingPromise);
          reject(new Error('本地项目索引正在被其他页面占用'));
        };
      });
      this.databasePromise = openingPromise;
    }
    return this.databasePromise;
  }

  private invalidateDatabase(database: IDBDatabase): void {
    this.clearDatabaseCache(database);
    try {
      database.close();
    } catch {
      // 连接可能已经被浏览器关闭，无需再次处理。
    }
  }

  private clearDatabaseCache(
    database?: IDBDatabase,
    openingPromise?: Promise<IDBDatabase>,
  ): void {
    if (database && this.activeDatabase !== database) {
      return;
    }
    if (openingPromise && this.databasePromise !== openingPromise) {
      return;
    }
    this.activeDatabase = undefined;
    this.databasePromise = undefined;
  }
}

const chunkIdRange = (projectId: string, path: string): IDBKeyRange => {
  const prefix = `${projectId}\u0000${path}\u0000`;
  return IDBKeyRange.bound(prefix, `${prefix}\uffff`);
};

/**
 * 兼容数据库第一版保存的摘要，升级后不要求用户重新建立索引才能打开页面。
 */
const normalizeProjectSummary = (summary: ProjectIndexSummary): ProjectIndexSummary => ({
  ...summary,
  addedFiles: summary.addedFiles ?? 0,
  updatedFiles: summary.updatedFiles ?? 0,
  unchangedFiles: summary.unchangedFiles ?? 0,
  removedFiles: summary.removedFiles ?? 0,
  changedPaths: summary.changedPaths ?? [],
  scanId: summary.scanId ?? '',
  lastCheckpointPath: summary.lastCheckpointPath ?? '',
});

const normalizeChunk = (chunk: IndexedProjectChunk): IndexedProjectChunk => ({
  ...chunk,
  symbols: chunk.symbols ?? [],
  imports: chunk.imports ?? [],
});

const requestResult = <T>(request: IDBRequest<T>): Promise<T> =>
  new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('本地索引操作失败'));
  });

const transactionComplete = (transaction: IDBTransaction): Promise<void> =>
  new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error('本地索引事务失败'));
    transaction.onabort = () => reject(transaction.error ?? new Error('本地索引事务已取消'));
  });

const isClosingConnectionError = (error: unknown): boolean =>
  error instanceof DOMException && error.name === 'InvalidStateError';

export const projectIndexRepository = new IndexedDbProjectIndexRepository();
