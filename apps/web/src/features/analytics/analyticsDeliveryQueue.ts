import type { AnalyticsClientEvent, AnalyticsClientEventType } from '@/types/api';

export const ANALYTICS_PENDING_PREFIX = 'prompt-optimizer.analytics.pending.v1:';
const INITIAL_RETRY_MS = 1000;
const MAX_RETRY_MS = 60_000;
const EVENT_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** 非安全上下文的浏览器可能不提供 randomUUID；仍用 Web Crypto 随机字节生成事件关联号。 */
export const createAnalyticsEventId = (): string => {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

interface PendingEvent {
  key: string;
  event: AnalyticsClientEvent;
}

export interface AnalyticsDeliveryDependencies {
  storage?: Storage;
  send: (event: AnalyticsClientEvent, signal: AbortSignal) => Promise<void>;
  online: () => boolean;
  now: () => Date;
  uuid: () => string;
  loginSessionId: () => string | null;
  warning: (reason: 'STORAGE_UNAVAILABLE' | 'INVALID_PENDING_EVENT' | 'DELIVERY_RETRY' | 'DELIVERY_REJECTED' | 'EVENT_CREATION_FAILED') => void;
  changed?: (pendingCount: number) => void;
  status: (error: unknown) => number | undefined;
}

/**
 * 按登录账号隔离浏览器未确认事件；每事件一个存储键，避免多标签页覆盖整份队列。
 * 自动重试有退避上限但不按次数删除事件；存储不可用时保留内存副本并明确告警。
 */
export const createAnalyticsDeliveryQueue = (dependencies: AnalyticsDeliveryDependencies) => {
  const memory = new Map<string, AnalyticsClientEvent>();
  const rejected = new Set<string>();
  const acknowledged = new Set<string>();
  let accountId: string | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | undefined;
  let generation = 0;
  let retryDelay = INITIAL_RETRY_MS;
  let storageWarningReported = false;
  let authenticationRejected = false;

  const warnStorage = (): void => {
    if (storageWarningReported) return;
    storageWarningReported = true;
    dependencies.warning('STORAGE_UNAVAILABLE');
  };

  const accountPrefix = (owner: string): string => `${ANALYTICS_PENDING_PREFIX}${encodeURIComponent(owner)}:`;

  /** 存储只接受固定事件结构；损坏记录保留原键以便排查，不执行自由文本或冒用其他账号。 */
  const decode = (raw: string, owner: string): AnalyticsClientEvent | undefined => {
    try {
      const value: unknown = JSON.parse(raw);
      if (typeof value !== 'object' || value === null) return undefined;
      const event = value as Record<string, unknown>;
      if (typeof event.eventId !== 'string' || !EVENT_ID_PATTERN.test(event.eventId)
          || (event.eventType !== 'APP_VISIT' && event.eventType !== 'RESULT_EXPORTED')
          || event.expectedUserId !== owner || typeof event.occurredAt !== 'string'
          || !Number.isFinite(Date.parse(event.occurredAt))
          || (event.expectedLoginSessionId !== null && event.expectedLoginSessionId !== undefined
            && (typeof event.expectedLoginSessionId !== 'string' || !EVENT_ID_PATTERN.test(event.expectedLoginSessionId)))) return undefined;
      return {
        eventId: event.eventId, eventType: event.eventType,
        occurredAt: event.occurredAt, expectedUserId: owner,
        expectedLoginSessionId: typeof event.expectedLoginSessionId === 'string' ? event.expectedLoginSessionId : null,
      };
    } catch {
      return undefined;
    }
  };

  const pending = (owner: string): PendingEvent[] => {
    const prefix = accountPrefix(owner);
    const events = new Map<string, AnalyticsClientEvent>();
    try {
      const storage = dependencies.storage;
      if (storage) {
        for (let index = 0; index < storage.length; index += 1) {
          const key = storage.key(index);
          if (!key?.startsWith(prefix)) continue;
          const raw = storage.getItem(key);
          if (!raw) continue;
          const event = decode(raw, owner);
          if (event && key === `${prefix}${event.eventId}`) events.set(key, event);
          else dependencies.warning('INVALID_PENDING_EVENT');
        }
      }
    } catch {
      warnStorage();
    }
    for (const [key, event] of memory) {
      if (key.startsWith(prefix)) events.set(key, event);
    }
    return [...events].filter(([key]) => !acknowledged.has(key)).map(([key, event]) => ({ key, event }))
      .sort((first, second) => first.event.occurredAt.localeCompare(second.event.occurredAt));
  };

  const notify = (): void => dependencies.changed?.(accountId ? pending(accountId).length : 0);

  const schedule = (delay: number): void => {
    if (timer !== undefined) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = undefined;
      void flush();
    }, delay);
  };

  /** 只在服务端确认接收后删去待发送记录；响应丢失时重放相同 ID，由服务端幂等处理。 */
  const acknowledge = (key: string): void => {
    // localStorage 删除失败也不能在本页面紧循环重发；保留磁盘副本供重载时幂等确认。
    memory.delete(key);
    rejected.delete(key);
    try {
      dependencies.storage?.removeItem(key);
    } catch {
      acknowledged.add(key);
      // 未能删去的持久化副本允许下次重放，不能为避免重复而丢掉未确认的其他事件。
      warnStorage();
    }
  };

  /** 网络、限流与服务端临时故障可恢复；认证/业务拒绝保留原事件，等待重新认证或修复。 */
  const flush = async (): Promise<void> => {
    if (!accountId || controller || authenticationRejected || !dependencies.online()) return;
    const owner = accountId;
    const capturedGeneration = generation;
    const next = pending(owner).find((entry) => !rejected.has(entry.key));
    notify();
    if (!next) return;
    const attempt = new AbortController();
    controller = attempt;
    try {
      await dependencies.send(next.event, attempt.signal);
      acknowledge(next.key);
      retryDelay = INITIAL_RETRY_MS;
    } catch (error: unknown) {
      if (attempt.signal.aborted) return;
      const status = dependencies.status(error);
      if (status === undefined || status === 408 || status === 429 || status >= 500) {
        dependencies.warning('DELIVERY_RETRY');
        if (generation === capturedGeneration) {
          schedule(retryDelay);
          retryDelay = Math.min(MAX_RETRY_MS, retryDelay * 2);
        }
        return;
      }
      // 不把 400/401/403 当成功；留在持久化队列，账号重新认证或页面重新加载后可恢复。
      rejected.add(next.key);
      if (status === 401 || status === 403 || status === 409) authenticationRejected = true;
      dependencies.warning('DELIVERY_REJECTED');
    } finally {
      if (controller === attempt) controller = undefined;
      notify();
    }
    if (generation === capturedGeneration && !authenticationRejected) schedule(50);
  };

  /** 只唤醒当前账号；旧账号的待发送事件保留，不能附带新账号的 Cookie 重放。 */
  const activateAccount = (owner: string | undefined): void => {
    if (owner === accountId) return;
    generation += 1;
    controller?.abort();
    controller = undefined;
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    accountId = owner;
    rejected.clear();
    authenticationRejected = false;
    retryDelay = INITIAL_RETRY_MS;
    notify();
    if (owner) schedule(0);
  };

  /** 事件不包含文件正文、邮箱、密码或认证令牌；落盘完成后再尝试发送。 */
  const enqueue = (eventType: AnalyticsClientEventType): void => {
    if (!accountId) return;
    let event: AnalyticsClientEvent;
    try {
      event = {
        eventId: dependencies.uuid(), eventType, occurredAt: dependencies.now().toISOString(), expectedUserId: accountId,
        // 只捕获发生时已知的关联号；离线事件不能用重登录后的地点补写历史。
        expectedLoginSessionId: dependencies.loginSessionId(),
      };
    } catch {
      // 采集初始化失败需明确提示，但不能把成功复制结果或成功导航改成业务失败。
      dependencies.warning('EVENT_CREATION_FAILED');
      return;
    }
    const key = `${accountPrefix(accountId)}${event.eventId}`;
    memory.set(key, event);
    try {
      if (dependencies.storage) dependencies.storage.setItem(key, JSON.stringify(event));
      else warnStorage();
    } catch {
      warnStorage();
    }
    notify();
    if (timer === undefined) schedule(0);
  };

  /** 联网、跨标签写入或显式恢复可唤醒队列；不改变任何待发送事件的 ID 和原始时间。 */
  const resume = (): void => {
    retryDelay = INITIAL_RETRY_MS;
    if (accountId && !authenticationRejected) schedule(0);
  };

  const dispose = (): void => {
    activateAccount(undefined);
    memory.clear();
  };

  return { activateAccount, enqueue, flush, resume, dispose };
};
