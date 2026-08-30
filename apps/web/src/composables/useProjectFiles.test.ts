import * as XLSX from 'xlsx';
import { describe, expect, it } from 'vitest';

import { readProjectFiles } from './useProjectFiles';

describe('readProjectFiles', () => {
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
      language: 'spreadsheet',
    });
    expect(selection.files[0]?.content).toContain('工作表：需求清单');
    expect(selection.files[0]?.content).toContain('提示词增强\tQingNiao');
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

  it('should list ignored unsupported suffixes in the warning', async () => {
    const pdf = new File(['%PDF-1.4'], '旧文档.pdf', { type: 'application/pdf' });

    const selection = await readProjectFiles([pdf]);

    expect(selection.files).toHaveLength(0);
    expect(selection.warnings[0]).toContain('.pdf');
  });
});
