import { httpClient } from './http';
import type { ApiResponse } from '@/types/api';
import type { PolishCapabilities, PolishDocument, PolishHistory, PolishInput, PolishOperation, PolishPolicy, PolishSource } from '@/types/textPolish';

const base = '/api/v1/text-polish';
/** 独立文章业务；返回业务数据，不让正文进入通用统计事件。 */
const data = async <T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> => (await request).data.data;
export const textPolishApi = {
  capabilities: (): Promise<PolishCapabilities> => data(httpClient.get(base + '/capabilities')),
  generate: (requestId: string, input: PolishInput, sourceMode: PolishSource = 'RAW', sourceRecordId?: string, expectedRevision?: number): Promise<PolishDocument> =>
    data(httpClient.post(base, { requestId, input, sourceMode, sourceRecordId, expectedRevision }, { timeout: 180_000 })),
  operation: (id: string): Promise<PolishOperation> => data(httpClient.get(base + '/operations/' + encodeURIComponent(id))),
  retrySave: (id: string): Promise<PolishDocument> => data(httpClient.post(base + '/operations/' + encodeURIComponent(id) + '/save')),
  history: (current = 1): Promise<PolishHistory> => data(httpClient.get(base + '/history', { params: { current, size: 10 } })),
  detail: (id: string): Promise<PolishDocument> => data(httpClient.get(base + '/history/' + encodeURIComponent(id))),
  edit: (id: string, polishedText: string, expectedRevision: number): Promise<PolishDocument> => data(httpClient.patch(base + '/history/' + encodeURIComponent(id), { polishedText, expectedRevision })),
  remove: (id: string): Promise<null> => data(httpClient.delete(base + '/history/' + encodeURIComponent(id))),
  policy: (): Promise<PolishPolicy> => data(httpClient.get('/api/v1/admin/models/text-polish')),
  savePolicy: (policy: PolishPolicy): Promise<PolishPolicy> => data(httpClient.put('/api/v1/admin/models/text-polish', policy)),
};
