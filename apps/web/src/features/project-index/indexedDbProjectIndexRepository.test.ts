import { afterEach, describe, expect, it, vi } from 'vitest';

import { IndexedDbProjectIndexRepository } from './indexedDbProjectIndexRepository';

describe('IndexedDbProjectIndexRepository connection recovery', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('should reopen IndexedDB when the cached connection is closing', async () => {
    let openCount = 0;
    const closingDatabase = createClosingDatabase();
    const healthyDatabase = createHealthyDatabase();

    vi.stubGlobal('indexedDB', {
      open: () => {
        openCount += 1;
        return createOpenRequest(openCount === 1 ? closingDatabase : healthyDatabase);
      },
    } satisfies Pick<IDBFactory, 'open'>);

    const repository = new IndexedDbProjectIndexRepository();

    await expect(repository.findProject('project-1')).resolves.toBeUndefined();
    expect(openCount).toBe(2);
  });
});

const createClosingDatabase = (): IDBDatabase => ({
  close: vi.fn(),
  transaction: () => {
    throw new DOMException(
      "Failed to execute 'transaction' on 'IDBDatabase': The database connection is closing.",
      'InvalidStateError',
    );
  },
} as unknown as IDBDatabase);

const createHealthyDatabase = (): IDBDatabase => {
  const transaction = {
    oncomplete: null,
    onerror: null,
    onabort: null,
    error: null,
    objectStore: () => ({
      get: () => {
        const request = {
          result: undefined,
          error: null,
          onsuccess: null,
          onerror: null,
        } as unknown as IDBRequest<undefined>;
        queueMicrotask(() => {
          request.onsuccess?.(new Event('success'));
          queueMicrotask(() => transaction.oncomplete?.(new Event('complete')));
        });
        return request;
      },
    }),
  } as unknown as IDBTransaction;

  return {
    close: vi.fn(),
    transaction: () => transaction,
  } as unknown as IDBDatabase;
};

const createOpenRequest = (database: IDBDatabase): IDBOpenDBRequest => {
  const request = {
    result: database,
    error: null,
    onsuccess: null,
    onerror: null,
    onblocked: null,
    onupgradeneeded: null,
  } as unknown as IDBOpenDBRequest;
  queueMicrotask(() => request.onsuccess?.(new Event('success')));
  return request;
};
