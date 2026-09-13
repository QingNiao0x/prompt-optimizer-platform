import type { AxiosProgressEvent } from 'axios';

import { httpClient } from './http';

import type { ApiResponse, DocumentUploadStatus } from '@/types/api';

export interface DocumentUploadCreatePayload {
  path: string;
  language: string;
  sizeBytes: number;
}

/** 创建大型文档临时索引任务，响应会告知服务端采用的分片大小。 */
export const createDocumentUpload = async (
  payload: DocumentUploadCreatePayload,
  signal?: AbortSignal,
): Promise<ApiResponse<DocumentUploadStatus>> => {
  const response = await httpClient.post<ApiResponse<DocumentUploadStatus>>(
    '/api/v1/context/documents',
    payload,
    { signal },
  );
  return response.data;
};

/** 上传一个固定编号的原始二进制分片；分片编号可安全重试。 */
export const uploadDocumentChunk = async (
  documentId: string,
  chunkIndex: number,
  chunk: Blob,
  onUploadProgress?: (event: AxiosProgressEvent) => void,
  signal?: AbortSignal,
): Promise<ApiResponse<DocumentUploadStatus>> => {
  const response = await httpClient.put<ApiResponse<DocumentUploadStatus>>(
    `/api/v1/context/documents/${documentId}/chunks/${chunkIndex}`,
    chunk,
    {
      signal,
      headers: { 'Content-Type': 'application/octet-stream' },
      onUploadProgress,
    },
  );
  return response.data;
};

/** 通知后端文件分片已经齐全，并开始异步解析和索引。 */
export const completeDocumentUpload = async (
  documentId: string,
  signal?: AbortSignal,
): Promise<ApiResponse<DocumentUploadStatus>> => {
  const response = await httpClient.post<ApiResponse<DocumentUploadStatus>>(
    `/api/v1/context/documents/${documentId}/complete`,
    undefined,
    { signal },
  );
  return response.data;
};

export const getDocumentUploadStatus = async (
  documentId: string,
  signal?: AbortSignal,
): Promise<ApiResponse<DocumentUploadStatus>> => {
  const response = await httpClient.get<ApiResponse<DocumentUploadStatus>>(
    `/api/v1/context/documents/${documentId}`,
    { signal },
  );
  return response.data;
};

/** 主动清除后端临时索引；即使未调用，服务端也会按 TTL 自动清理。 */
export const deleteDocumentUpload = async (documentId: string): Promise<void> => {
  await httpClient.delete(`/api/v1/context/documents/${documentId}`);
};
