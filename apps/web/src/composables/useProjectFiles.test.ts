import * as XLSX from 'xlsx';
import { describe, expect, it } from 'vitest';

import {
  collectCandidateFiles,
  shouldUseTemporaryDocumentIndex,
} from '../workers/fileReaderCore';
import { readProjectFiles } from './useProjectFiles';

describe('readProjectFiles', () => {
  it('should exclude dependency cache paths in fallback folder uploads while retaining project facts', async () => {
    const ignored = ['demo/.m2/repository/dependency/pom.xml',
      'demo/apps/web/.npm-cache/index.json', 'demo/.NPM/_cacache/cache.txt',
      'demo/.pnpm-store/metadata.json'];
    const retained = ['demo/pom.xml', 'demo/apps/web/package.json',
      'demo/docs/cache-design.md', 'demo/business-cache/rules.txt'];
    const files = [...ignored, ...retained].map((path) => {
      const file = new File(['合成测试内容'], path.split('/').at(-1)!);
      Object.defineProperty(file, 'webkitRelativePath', { value: path });
      return file;
    });

    const selection = await collectCandidateFiles(files, 1_000);

    expect(selection.files.map((file) => file.webkitRelativePath)).toEqual(retained);
    expect(selection.stats.pathIgnored).toBe(ignored.length);
  });

  it('should extract worksheet text from an xlsx file instead of ignoring it as binary content', async () => {
    const workbook = XLSX.utils.book_new();
    const worksheet = XLSX.utils.aoa_to_sheet([
      ['模块', '负责人'],
      ['提示词增强', 'QingNiao'],
    ]);
    XLSX.utils.book_append_sheet(workbook, worksheet, '需求清单');
    const file = new File(
      [XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })],
      '需求清单.xlsx',
      { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
    );

    const selection = await readProjectFiles([file]);

    expect(selection.warnings).toEqual([]);
    expect(selection.files).toHaveLength(1);
    expect(selection.files[0]).toMatchObject({
      path: '需求清单.xlsx',
      language: 'xlsx',
    });
    expect(selection.files[0]?.content).toContain('工作表：需求清单');
    expect(selection.files[0]?.content).toContain('提示词增强\tQingNiao');
  });

  it('should route office files and large text documents to the temporary full-text index', () => {
    const docx = new File(['PK'], '需求说明.docx');
    const largeText = new File([new Uint8Array(1_000_001)], '论文.txt');
    const source = new File(['class Demo {}'], 'Demo.java');

    expect(shouldUseTemporaryDocumentIndex(docx)).toBe(true);
    expect(shouldUseTemporaryDocumentIndex(largeText)).toBe(true);
    expect(shouldUseTemporaryDocumentIndex(source)).toBe(false);
  });

  it('should index text documents before the inline reader can discard their tail', () => {
    const text = new File(['前言'.repeat(32_001), '尾部：退费规则为三个工作日'], '方案.txt');
    expect(shouldUseTemporaryDocumentIndex(text)).toBe(true);
  });

  it.each(['../方案.txt', '资料/../../方案.txt', 'C:方案.txt', '/资料/方案.txt'])
    ('should reject unsafe relative paths before reading: %s', async (path) => {
      const file = new File(['无需读取'], '方案.txt');
      Object.defineProperty(file, 'webkitRelativePath', { value: path });
      const selection = await collectCandidateFiles([file], 1_000);
      expect(selection.files).toHaveLength(0);
      expect(selection.stats.pathIgnored).toBe(1);
    });

  it('should retain nested mixed office files and exclude sensitive files in a folder', async () => {
    const paths = ['资料/说明.txt', '资料/子目录/方案.docx', '资料/子目录/报告.pdf',
      '资料/.env', '资料/id_rsa'];
    const files = paths.map((path) => {
      const file = new File(['合成测试内容'], path.split('/').at(-1)!);
      Object.defineProperty(file, 'webkitRelativePath', { value: path });
      return file;
    });
    const selection = await collectCandidateFiles(files, 1_000);
    expect(selection.files.map((file) => file.webkitRelativePath)).toEqual(paths.slice(0, 3));
    expect(selection.stats.sensitive).toBe(2);
  });

  it('should exclude production configuration before reading', async () => {
    const result = await collectCandidateFiles([new File(['synthetic'], 'application-prod.yml')], 1_000);
    expect(result.files).toHaveLength(0);
    expect(result.stats.sensitive).toBe(1);
  });

  it('should encode docx files as base64 so the backend can extract text', async () => {
    const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x06, 0x00]);
    const file = new File([bytes], '需求说明.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });

    const selection = await readProjectFiles([file]);

    expect(selection.files).toHaveLength(1);
    expect(selection.files[0]).toMatchObject({
      path: '需求说明.docx',
      language: 'docx',
    });
    expect(selection.files[0]?.content).toBe(
      btoa(String.fromCharCode(...bytes)),
    );
    expect(selection.warnings).toEqual([]);
  });

  it('should pass pdf bytes to the backend parser instead of rejecting the file in the browser', async () => {
    const pdf = new File(['%PDF-1.4'], '旧文档.pdf', { type: 'application/pdf' });

    const selection = await readProjectFiles([pdf]);

    expect(selection.files).toHaveLength(1);
    expect(selection.files[0]).toMatchObject({
      path: '旧文档.pdf',
      language: 'pdf',
    });
    expect(selection.warnings).toEqual([]);
  });

  it('should exclude credentials and environment files before reading their content', async () => {
    const envFile = new File(['MODEL_API_KEY=secret-value'], '.env.local', { type: 'text/plain' });
    const credentialsFile = new File(['{"token":"secret-value"}'], 'credentials.json', {
      type: 'application/json',
    });

    const fileList = {
      0: envFile,
      1: credentialsFile,
      length: 2,
      item: (index: number) => index === 0 ? envFile : index === 1 ? credentialsFile : null,
    } as unknown as FileList;
    const selection = await collectCandidateFiles(fileList, 1_000);

    expect(selection.files).toHaveLength(0);
    expect(selection.stats.sensitive).toBe(2);
  });

  it('should accept a registry-only npmrc and reject one containing an auth token', async () => {
    const safe = new File(['registry=https://registry.npmjs.org/'], '.npmrc', { type: 'text/plain' });
    const secret = new File(['//registry.npmjs.org/:_authToken=secret-token'], '.npmrc', {
      type: 'text/plain',
    });

    const selection = await readProjectFiles([safe, secret]);

    expect(selection.files).toHaveLength(1);
    expect(selection.files[0]?.content).toContain('registry.npmjs.org');
    expect(selection.warnings).toContain(
      '已忽略 1 个敏感文件（密钥、私钥、.env 或凭据文件），不会上传。',
    );
  });
});
