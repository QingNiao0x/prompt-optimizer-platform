import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';

/** 实际浏览器HTTP分片请求，服务端以受控响应模拟断线与确认清单。 */
test('短暂传输故障后查询确认清单并只补传缺失分片', async ({ page }) => {
  await mockAuthentication(page);
  await page.route('**/__document_recovery', route => route.fulfill({ contentType: 'text/html', body: '<title>分片恢复</title>' }));
  const status = { documentId: 'recovery', path: 'sample.txt', language: 'text', phase: 'UPLOADING',
    fileSizeBytes: 8, uploadedBytes: 0, progressPercent: 0, extractedCharacters: 0, chunkCount: 0,
    summary: '', warnings: [], errorMessage: '', expiresAt: '2099-01-01T00:00:00Z', chunkSizeBytes: 4, receivedChunks: [] as number[] };
  let attempts = 0, firstChunk = 0, statusQueries = 0;
  const saved = new Map<number, Buffer>();
  await page.route('**/api/v1/context/documents**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.includes('/chunks/')) {
      const index = Number(path.split('/').at(-1));
      if (index === 1 && ++attempts <= 3) { await route.fulfill({ status: 503, json: { error: { message: '受控临时故障' } } }); return; }
      if (index === 0) firstChunk++;
      saved.set(index, route.request().postDataBuffer()!);
      status.receivedChunks = [...saved.keys()];
      status.uploadedBytes = [...saved.values()].reduce((sum, bytes) => sum + bytes.length, 0);
    } else if (path.endsWith('/complete')) {
      expect(status.uploadedBytes).toBe(8);
      status.phase = 'READY'; status.progressPercent = 100;
    } else if (route.request().method() === 'GET') statusQueries++;
    await route.fulfill({ json: { data: status } });
  });
  await page.goto('/__document_recovery');
  const result = await page.evaluate(async () => {
    const path = '/src/features/document-upload/documentUploader.ts';
    const { uploadAndIndexDocument } = await import(path);
    return (await uploadAndIndexDocument(new File(['01234567'], 'sample.txt'), 'sample.txt', 'text')).status.phase;
  });
  expect(result).toBe('READY');
  expect(firstChunk).toBe(1); expect(attempts).toBe(4); expect(statusQueries).toBe(1);
  expect(Buffer.concat([saved.get(0)!, saved.get(1)!]).toString()).toBe('01234567');
});
