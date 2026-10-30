import type {
  IndexedProjectChunk,
  IndexedProjectFile,
  ProjectIndexRepository,
  ProjectIndexSummary,
} from './projectIndexer';
import type { DirectoryHandleLike } from './fileSystemDirectorySource';
import { referencedPathVariants } from './projectPathReferences';
import {
  SEARCH_STORE, SEARCH_KEY_STORE, createSearchStores, migrateChunkSearch, chunkBodyRecord,
  lookupSearchChunkIds, removeSearchFiles, removeProjectSearch, upsertSearchIndex,
} from './projectIndexSearchIndex';

const DATABASE_NAME = 'prompt-optimizer-project-index';
const DATABASE_VERSION = 4;
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
    checkpoint?: ProjectIndexSummary,
  ): Promise<void> {
    if (!checkpoint && files.length === 0 && chunks.length === 0 && replacedPaths.length === 0) {
      return;
    }
    const transaction = await this.createTransaction(
      [FILE_STORE, CHUNK_STORE, SEARCH_STORE, SEARCH_KEY_STORE, PROJECT_STORE], 'readwrite',
    );
    const completion = transactionComplete(transaction);
    // 下方等待索引请求时事务可能先失败；仍由最后的 await 传播原始事务异常。
    void completion.catch(() => undefined);
    const fileStore = transaction.objectStore(FILE_STORE);
    const chunkStore = transaction.objectStore(CHUNK_STORE);
    const projectId = files[0]?.projectId ?? chunks[0]?.projectId;
    try {
      if (projectId) {
        await removeSearchFiles(transaction, projectId, replacedPaths);
        for (const path of replacedPaths) {
          chunkStore.delete(chunkIdRange(projectId, path));
        }
      }
      for (const file of files) fileStore.put(file);
      for (const chunk of chunks) chunkStore.put(chunkBodyRecord(chunk));
      await upsertSearchIndex(transaction, chunks);
      if (checkpoint) transaction.objectStore(PROJECT_STORE).put(checkpoint);
      await completion;
    } catch (error) {
      // 同步 put 错误也必须回滚，不能只保存正文而漏掉倒排关联。
      try { transaction.abort(); } catch { /* 事务可能已经失败并回滚。 */ }
      throw transaction.error ?? error;
    }
  }

  async pauseProject(summary: ProjectIndexSummary): Promise<void> {
    await this.putProject(summary);
  }

  async completeProject(summary: ProjectIndexSummary): Promise<void> {
    await this.putProject(summary);
  }

  async removeUnseenFiles(projectId: string, scanId: string): Promise<number> {
    const transaction = await this.createTransaction(
      [FILE_STORE, CHUNK_STORE, SEARCH_STORE, SEARCH_KEY_STORE], 'readwrite',
    );
    const completion = transactionComplete(transaction);
    const fileStore = transaction.objectStore(FILE_STORE);
    const chunkStore = transaction.objectStore(CHUNK_STORE);
    const index = fileStore.index('projectScan');
    let removedFiles = 0;

    const removeFromCursor = (request: IDBRequest<IDBCursorWithValue | null>): Promise<void> => new Promise((resolve, reject) => {
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) {
          resolve();
          return;
        }
        const file = cursor.value as IndexedProjectFile;
        // 每次只清理一个文件涉及的词块，避免删除大目录时把全部源码读回内存。
        void removeSearchFiles(transaction, projectId, [file.path]).then(() => {
          cursor.delete();
          chunkStore.delete(chunkIdRange(projectId, file.path));
          removedFiles += 1;
          cursor.continue();
        }).catch(reject);
      };
      request.onerror = () => reject(request.error);
    });

    void completion.catch(() => undefined);
    try {
      // 两个扫描范围串行清理；并行更新同一个词块会把另一文件已删除的关联写回。
      await removeFromCursor(index.openCursor(IDBKeyRange.bound(
        [projectId, ''],
        [projectId, scanId],
        false,
        true,
      )));
      await removeFromCursor(index.openCursor(IDBKeyRange.bound(
        [projectId, scanId],
        [projectId, '\uffff'],
        true,
        false,
      )));
      await completion;
      return removedFiles;
    } catch (error) {
      try { transaction.abort(); } catch { /* 事务可能已经回滚。 */ }
      throw transaction.error ?? error;
    }
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
    if (limit <= 0) return [];
    const unique = new Map<string, IndexedProjectChunk>();
    const priorityChunks = await this.getChunksByPriority(projectId, Math.min(limit, 40));
    for (const chunk of priorityChunks) {
      unique.set(chunk.id, chunk);
    }

    // 均衡覆盖原始需求和末尾确认答案，避免前几个常见词耗尽候选名额。
    const distinctTerms = Array.from(new Set(searchTerms));
    const terms = distinctTerms.length <= 64 ? distinctTerms
      : [...distinctTerms.slice(0, 32), ...distinctTerms.slice(-32)];
    const hits = new Map<string, number>();
    const perTermLimit = Math.min(100, Math.max(8, Math.ceil(limit * 2 / Math.max(1, terms.length))));
    for (let start = 0; start < terms.length; start += 8) {
      const groups = await Promise.all(terms.slice(start, start + 8)
        .map((term) => this.getChunksBySearchTerm(`${projectId}:${term}`, perTermLimit)));
      for (const matches of groups) {
        for (const chunk of matches) {
          unique.set(chunk.id, chunk);
          hits.set(chunk.id, (hits.get(chunk.id) ?? 0) + 1);
        }
      }
    }

    if (unique.size === 0) {
      const fallback = await this.getChunksByProject(projectId, limit);
      for (const chunk of fallback) {
        unique.set(chunk.id, chunk);
      }
    }
    return Array.from(unique.values())
      .sort((left, right) => (hits.get(right.id) ?? 0) - (hits.get(left.id) ?? 0)
        || left.priority - right.priority || left.id.localeCompare(right.id))
      .slice(0, limit);
  }

  async findChunksByPaths(
    projectId: string,
    paths: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    if (paths.length === 0 || limit <= 0) {
      return [];
    }
    const project = await this.findProject(projectId);
    const variants = Array.from(new Set(paths.flatMap((path) => referencedPathVariants(path, project?.rootName))));
    if (variants.length === 0) return [];
    // 先从文件元数据解析文件/目录，不扫描源码全文，也不让普通词候选名额挤掉明确目标。
    const fileTransaction = await this.createTransaction(FILE_STORE, 'readonly');
    const fileCompletion = transactionComplete(fileTransaction);
    const fileIndex = fileTransaction.objectStore(FILE_STORE).index('projectPath');
    const fileRequests = variants.flatMap((path) => [
      requestResult<IndexedProjectFile[]>(fileIndex.getAll(IDBKeyRange.only([projectId, path]), 1)),
      requestResult<IndexedProjectFile[]>(fileIndex.getAll(
        IDBKeyRange.bound([projectId, `${path}/`], [projectId, `${path}/\uffff`]), limit,
      )),
    ]);
    const fileGroups = await Promise.all(fileRequests);
    await fileCompletion;
    const resolvedPaths = Array.from(new Set(fileGroups.flat().map((file) => file.path))).slice(0, limit);
    if (resolvedPaths.length === 0) return [];
    const transaction = await this.createTransaction([CHUNK_STORE, SEARCH_STORE], 'readonly');
    const completion = transactionComplete(transaction);
    const index = transaction.objectStore(CHUNK_STORE).index('projectPath');
    const perPathLimit = Math.max(1, Math.ceil(limit / resolvedPaths.length));
    const requests = resolvedPaths.map((path) =>
      requestResult<IndexedProjectChunk[]>(
        index.getAll(IDBKeyRange.only([projectId, path]), perPathLimit),
      ));
    const groups = await Promise.all((await Promise.all(requests)).map((chunks) => hydrateChunkTerms(transaction, chunks)));
    await completion;
    // 同一目录先覆盖不同文件，再补后续片段，避免目录首个大文件占满召回预算。
    const result: IndexedProjectChunk[] = [];
    for (let offset = 0; offset < perPathLimit && result.length < limit; offset += 1) {
      for (const group of groups) {
        const chunk = group[offset];
        if (chunk && result.length < limit) result.push(normalizeChunk(chunk));
      }
    }
    return result;
  }

  async deleteProject(projectId: string): Promise<void> {
    const transaction = await this.createTransaction(
      [PROJECT_STORE, FILE_STORE, CHUNK_STORE, SOURCE_STORE, SEARCH_STORE, SEARCH_KEY_STORE],
      'readwrite',
    );
    const completion = transactionComplete(transaction);
    void completion.catch(() => undefined);
    try {
      await removeProjectSearch(transaction, projectId);
      transaction.objectStore(FILE_STORE).delete(projectRecordRange(projectId));
      transaction.objectStore(CHUNK_STORE).delete(projectRecordRange(projectId));
      transaction.objectStore(PROJECT_STORE).delete(projectId);
      transaction.objectStore(SOURCE_STORE).delete(projectId);
      await completion;
    } catch (error) {
      try { transaction.abort(); } catch { /* 事务可能已经回滚。 */ }
      throw transaction.error ?? error;
    }
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
   * 只清理到期索引；失败任务的已提交批次仍可恢复，不能在重试前自动删除。
   */
  async cleanupProjects(now = new Date()): Promise<number> {
    const projects = await this.listProjects();
    const expired = projects.filter((project) => {
      const expiresAt = Date.parse(project.expiresAt);
      const reachedExpiry = Number.isFinite(expiresAt) && expiresAt <= now.getTime();
      return reachedExpiry;
    });
    for (const project of expired) {
      await this.deleteProject(project.id);
    }
    return expired.length;
  }

  async deleteAllProjects(): Promise<void> {
    const transaction = await this.createTransaction(
      [PROJECT_STORE, FILE_STORE, CHUNK_STORE, SOURCE_STORE, SEARCH_STORE, SEARCH_KEY_STORE],
      'readwrite',
    );
    const completion = transactionComplete(transaction);
    transaction.objectStore(PROJECT_STORE).clear();
    transaction.objectStore(FILE_STORE).clear();
    transaction.objectStore(CHUNK_STORE).clear();
    transaction.objectStore(SOURCE_STORE).clear();
    transaction.objectStore(SEARCH_STORE).clear();
    transaction.objectStore(SEARCH_KEY_STORE).clear();
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
    const transaction = await this.createTransaction([CHUNK_STORE, SEARCH_STORE, SEARCH_KEY_STORE], 'readonly');
    const completion = transactionComplete(transaction);
    void completion.catch(() => undefined);
    const ids = await lookupSearchChunkIds(transaction, searchTerm, limit);
    const chunks = await Promise.all(ids.map((id) => requestResult<IndexedProjectChunk | undefined>(
      transaction.objectStore(CHUNK_STORE).get(id),
    )));
    const result = await hydrateChunkTerms(transaction,
      chunks.filter((chunk): chunk is IndexedProjectChunk => chunk !== undefined));
    await completion;
    return result;
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
    const transaction = await this.createTransaction([CHUNK_STORE, SEARCH_STORE], 'readonly');
    const completion = transactionComplete(transaction);
    const result = await requestResult<IndexedProjectChunk[]>(
      transaction.objectStore(CHUNK_STORE).index(indexName).getAll(query, limit),
    );
    const chunks = await hydrateChunkTerms(transaction, result);
    await completion;
    return chunks;
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
        request.onupgradeneeded = (event) => {
          const database = request.result;
          if (!database.objectStoreNames.contains(PROJECT_STORE)) {
            database.createObjectStore(PROJECT_STORE, { keyPath: 'id' });
          }
          let fileStore: IDBObjectStore;
          if (!database.objectStoreNames.contains(FILE_STORE)) {
            fileStore = database.createObjectStore(FILE_STORE, { keyPath: 'id' });
            fileStore.createIndex('projectId', 'projectId');
            fileStore.createIndex('projectPath', ['projectId', 'path'], { unique: true });
          } else {
            fileStore = request.transaction!.objectStore(FILE_STORE);
          }
          if (!fileStore.indexNames.contains('projectScan')) {
            fileStore.createIndex('projectScan', ['projectId', 'lastSeenScanId']);
          }
          if ((event as IDBVersionChangeEvent).oldVersion < 3) {
            migrateLegacyFileScanIds(fileStore);
          }
          let chunkStore: IDBObjectStore;
          if (!database.objectStoreNames.contains(CHUNK_STORE)) {
            chunkStore = database.createObjectStore(CHUNK_STORE, { keyPath: 'id' });
            chunkStore.createIndex('projectId', 'projectId');
            chunkStore.createIndex('projectPath', ['projectId', 'path']);
            chunkStore.createIndex('projectPriority', ['projectId', 'priority']);
          } else {
            chunkStore = request.transaction!.objectStore(CHUNK_STORE);
          }
          if ((event as IDBVersionChangeEvent).oldVersion < 4) {
            createSearchStores(database);
            if (chunkStore.indexNames.contains('searchTerms')) chunkStore.deleteIndex('searchTerms');
            migrateChunkSearch(request.transaction!, chunkStore);
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

const projectRecordRange = (projectId: string): IDBKeyRange => {
  const prefix = `${projectId}\u0000`;
  return IDBKeyRange.bound(prefix, `${prefix}\uffff`);
};

/**
 * 旧版本记录可能没有 lastSeenScanId。补空值后，新复合索引才能找到并清理这些记录。
 */
const migrateLegacyFileScanIds = (fileStore: IDBObjectStore): void => {
  const request = fileStore.openCursor();
  request.onsuccess = () => {
    const cursor = request.result;
    if (!cursor) {
      return;
    }
    const file = cursor.value as IndexedProjectFile;
    if (typeof file.lastSeenScanId !== 'string') {
      cursor.update({ ...file, lastSeenScanId: '' });
    }
    cursor.continue();
  };
  request.onerror = () => fileStore.transaction.abort();
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

/** 正文与完整词表从同一个只读事务恢复，调用方仍得到原有完整片段。 */
const hydrateChunkTerms = async (transaction: IDBTransaction, chunks: IndexedProjectChunk[]): Promise<IndexedProjectChunk[]> => {
  const index = transaction.objectStore(SEARCH_STORE).index('chunkId');
  return Promise.all(chunks.map(async (chunk) => {
    const search = await requestResult<{ searchTerms: string[] } | undefined>(index.get(chunk.id));
    if (!search) throw new Error('本地全文词表不完整，请重新建立项目索引');
    return normalizeChunk({ ...chunk, searchTerms: search.searchTerms });
  }));
};

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
