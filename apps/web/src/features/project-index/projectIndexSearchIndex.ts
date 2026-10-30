import type { IndexedProjectChunk } from './projectIndexer';

export const SEARCH_STORE = 'chunkSearch';
export const SEARCH_KEY_STORE = 'chunkSearchKeys';
const KEY_BLOCK_SIZE = 256;
const MIGRATION_TERM_BATCH = 64_000;

interface SearchKeyBlock {
  block: number;
  ids: Record<number, string>;
}

/**
 * 全文词表的原生多值索引改用短数字主键，避免每个词重复保存长文件路径。
 * 正文保留在 chunks，完整词表只存一份在 chunkSearch；数字到原片段 ID 的映射独立分块。
 */
export const createSearchStores = (database: IDBDatabase): void => {
  const search = database.createObjectStore(SEARCH_STORE, { keyPath: 'key', autoIncrement: true });
  search.createIndex('chunkId', 'id', { unique: true });
  search.createIndex('searchTerms', 'searchTerms', { multiEntry: true });
  database.createObjectStore(SEARCH_KEY_STORE, { keyPath: 'block' });
};

/** 正文记录不重复保存完整词表；仓库读取时从同事务的词索引补齐，外部片段契约保持不变。 */
export const chunkBodyRecord = (chunk: IndexedProjectChunk): Omit<IndexedProjectChunk, 'searchTerms'> => {
  const { searchTerms: _searchTerms, ...body } = chunk;
  return body;
};

/** 同 ID 覆盖沿用数字键，词索引、映射与正文在调用方的同一事务中提交或回滚。 */
export const upsertSearchIndex = async (transaction: IDBTransaction, chunks: IndexedProjectChunk[]): Promise<void> => {
  const store = transaction.objectStore(SEARCH_STORE);
  const unique = new Map(chunks.map((chunk) => [chunk.id, chunk]));
  const pairs = await Promise.all(Array.from(unique.values(), async (chunk): Promise<[number, string]> => {
    const existing = await readRequest(store.index('chunkId').getKey(chunk.id));
    const value = { id: chunk.id, searchTerms: chunk.searchTerms };
    const key = await readRequest(store.put(existing === undefined ? value : { ...value, key: existing }));
    return [key as number, chunk.id];
  }));
  await updateKeyBlocks(transaction, pairs, []);
};

/** 文件覆盖和增量删除只移除该路径的关联，不影响同一个映射块中的其他文件或项目。 */
export const removeSearchFiles = async (
  transaction: IDBTransaction, projectId: string, paths: string[],
): Promise<void> => {
  for (const path of new Set(paths)) {
    const prefix = `${projectId}\0${path}\0`;
    await removeSearchRange(transaction, IDBKeyRange.bound(prefix, `${prefix}\uffff`));
  }
};

/** 按项目 ID 范围分批删除词关联，避免删除整个站点或一次读回全部项目正文。 */
export const removeProjectSearch = async (transaction: IDBTransaction, projectId: string): Promise<void> => {
  await removeSearchRange(transaction, IDBKeyRange.bound(`${projectId}\0`, `${projectId}\0\uffff`));
};

/**
 * 数字键仅用于压缩存储，不作为召回排序依据。
 * 只读取数字键及有界映射块，按原字符串主键归并后取限额，保持旧查询的候选范围。
 */
export const lookupSearchChunkIds = async (
  transaction: IDBTransaction, term: string, limit: number,
): Promise<string[]> => {
  const keys = await readRequest(transaction.objectStore(SEARCH_STORE).index('searchTerms').getAllKeys(term)) as number[];
  const blocks = transaction.objectStore(SEARCH_KEY_STORE);
  let selected: string[] = [];
  for (let offset = 0; offset < keys.length; offset += KEY_BLOCK_SIZE) {
    const batch = keys.slice(offset, offset + KEY_BLOCK_SIZE);
    const blockNumbers = new Set(batch.map(blockOf));
    const rows = await Promise.all(Array.from(blockNumbers, (block) => readRequest<SearchKeyBlock | undefined>(blocks.get(block))));
    const byBlock = new Map(rows.filter((row): row is SearchKeyBlock => row !== undefined).map((row) => [row.block, row.ids]));
    const ids = batch.map((key) => {
      const id = byBlock.get(blockOf(key))?.[key % KEY_BLOCK_SIZE];
      if (id === undefined) throw new Error('本地索引映射不完整，请重新建立项目索引');
      return id;
    });
    selected = Array.from(new Set([...selected, ...ids])).sort().slice(0, limit);
  }
  return selected;
};

/** 逐批迁移旧完整词表；升级事务失败整体回滚，不先清空已保存的源码。 */
export const migrateChunkSearch = (transaction: IDBTransaction, chunks: IDBObjectStore): void => {
  let pending: IndexedProjectChunk[] = [];
  let termReferences = 0;
  const request = chunks.openCursor();
  request.onsuccess = () => {
    const cursor = request.result;
    if (cursor) {
      const chunk = cursor.value as IndexedProjectChunk;
      pending.push(chunk);
      termReferences += chunk.searchTerms.length;
      cursor.update(chunkBodyRecord(chunk));
    }
    if (!cursor || pending.length >= KEY_BLOCK_SIZE || termReferences >= MIGRATION_TERM_BATCH) {
      const batch = pending;
      pending = [];
      termReferences = 0;
      void upsertSearchIndex(transaction, batch).then(() => cursor?.continue()).catch(() => transaction.abort());
    } else cursor.continue();
  };
  request.onerror = () => transaction.abort();
};

/** 删除后重复读取范围内下一批；数字键和对应映射同步移除，不扫描其他项目正文。 */
const removeSearchRange = async (transaction: IDBTransaction, range: IDBKeyRange): Promise<void> => {
  const store = transaction.objectStore(SEARCH_STORE);
  for (;;) {
    const keys = await readRequest(store.index('chunkId').getAllKeys(range, KEY_BLOCK_SIZE)) as number[];
    if (keys.length === 0) return;
    for (const key of keys) store.delete(key);
    await updateKeyBlocks(transaction, [], keys);
  }
};

/** 每块最多 256 个原 ID；先移除旧键再写入新键，同块跨文件或跨项目时保留未修改的映射。 */
const updateKeyBlocks = async (
  transaction: IDBTransaction, pairs: [number, string][], removed: number[],
): Promise<void> => {
  const changes = new Map<number, { puts: [number, string][]; removes: number[] }>();
  const group = (key: number) => {
    const block = blockOf(key);
    const change = changes.get(block) ?? { puts: [], removes: [] };
    changes.set(block, change);
    return change;
  };
  for (const [key, id] of pairs) group(key).puts.push([key % KEY_BLOCK_SIZE, id]);
  for (const key of removed) group(key).removes.push(key % KEY_BLOCK_SIZE);
  const store = transaction.objectStore(SEARCH_KEY_STORE);
  await Promise.all(Array.from(changes, async ([block, change]) => {
    const row = await readRequest<SearchKeyBlock | undefined>(store.get(block)) ?? { block, ids: {} };
    for (const key of change.removes) delete row.ids[key];
    for (const [key, id] of change.puts) row.ids[key] = id;
    if (Object.keys(row.ids).length) store.put(row);
    else store.delete(block);
  }));
};

const blockOf = (key: number): number => Math.floor(key / KEY_BLOCK_SIZE);

const readRequest = <T>(request: IDBRequest<T>): Promise<T> => new Promise((resolve, reject) => {
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error ?? new Error('本地全文检索索引操作失败'));
});
