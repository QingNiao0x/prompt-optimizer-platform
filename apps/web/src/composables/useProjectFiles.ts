import { readonly, ref } from 'vue';

import type { ContextFileInput } from '@/types/api';

const MAX_FILES = 200;
const MAX_FILE_BYTES = 300_000;
const MAX_SPREADSHEET_SHEETS = 8;
const MAX_SPREADSHEET_ROWS = 200;
const MAX_SPREADSHEET_COLUMNS = 30;
const MAX_SPREADSHEET_CHARACTERS = 120_000;
const SUPPORTED_EXTENSIONS = new Set([
  'css', 'env.example', 'go', 'gradle', 'html', 'java', 'js', 'json', 'jsx',
  'kt', 'md', 'properties', 'py', 'rs', 'scss', 'sql', 'ts', 'tsx', 'txt',
  'vue', 'xls', 'xlsx', 'xml', 'yaml', 'yml',
]);

const LANGUAGE_BY_EXTENSION: Record<string, string> = {
  css: 'css',
  go: 'go',
  gradle: 'gradle',
  html: 'html',
  java: 'java',
  js: 'javascript',
  json: 'json',
  jsx: 'javascript',
  kt: 'kotlin',
  md: 'markdown',
  properties: 'properties',
  py: 'python',
  rs: 'rust',
  scss: 'scss',
  sql: 'sql',
  ts: 'typescript',
  tsx: 'typescript',
  txt: 'text',
  vue: 'vue',
  xls: 'spreadsheet',
  xlsx: 'spreadsheet',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yaml',
};

export interface ProjectFileSelection {
  files: ContextFileInput[];
  warnings: string[];
}

type SupportedFileList = FileList | readonly File[];

const getExtension = (fileName: string): string => {
  const normalizedName = fileName.toLowerCase();
  if (normalizedName.endsWith('.env.example')) {
    return 'env.example';
  }
  const separatorIndex = normalizedName.lastIndexOf('.');
  return separatorIndex >= 0 ? normalizedName.slice(separatorIndex + 1) : '';
};

/**
 * 将表格的前几张工作表转换为纯文本，让后端上下文分析器可以像处理代码文件一样处理表格内容。
 *
 * 不直接上传二进制内容；行、列、工作表和总字符数均有限制，避免大型导出表拖慢浏览器或模型请求。
 */
const readSpreadsheetContent = async (file: File): Promise<string> => {
  // 表格解析库体积较大，只有用户实际选择 Excel 文件时才下载，避免影响普通代码文件场景的首屏加载。
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellText: true });
  const sections: string[] = [`# 表格文件：${file.name}`];
  let remainingCharacters = MAX_SPREADSHEET_CHARACTERS;

  for (const sheetName of workbook.SheetNames.slice(0, MAX_SPREADSHEET_SHEETS)) {
    if (remainingCharacters <= 0) {
      break;
    }

    const worksheet = workbook.Sheets[sheetName];
    if (!worksheet) {
      continue;
    }
    const rows = XLSX.utils.sheet_to_json<unknown[]>(worksheet, {
      header: 1,
      raw: false,
      blankrows: false,
      defval: '',
    });
    const tableText = rows
      .slice(0, MAX_SPREADSHEET_ROWS)
      .map((row) => row
        .slice(0, MAX_SPREADSHEET_COLUMNS)
        .map((cell) => String(cell).replace(/[\t\r\n]+/g, ' ').trim())
        .join('\t'))
      .filter((row) => row.trim().length > 0)
      .join('\n')
      .slice(0, remainingCharacters);

    if (tableText) {
      sections.push(`## 工作表：${sheetName}\n${tableText}`);
      remainingCharacters -= tableText.length;
    }
  }

  return sections.join('\n\n');
};

const readSupportedFileContent = async (file: File, extension: string): Promise<string> => {
  if (extension === 'xls' || extension === 'xlsx') {
    return readSpreadsheetContent(file);
  }
  return file.text();
};

export const readProjectFiles = async (fileList: SupportedFileList): Promise<ProjectFileSelection> => {
  const candidates = Array.from(fileList).slice(0, MAX_FILES);
  const warnings: string[] = [];
  const acceptedFiles: ContextFileInput[] = [];
  let unsupportedCount = 0;
  let oversizedCount = 0;
  let failedCount = 0;

  for (const file of candidates) {
    const extension = getExtension(file.name);
    if (!SUPPORTED_EXTENSIONS.has(extension)) {
      unsupportedCount += 1;
      continue;
    }
    if (file.size > MAX_FILE_BYTES) {
      oversizedCount += 1;
      continue;
    }

    try {
      acceptedFiles.push({
        path: file.webkitRelativePath || file.name,
        content: await readSupportedFileContent(file, extension),
        language: LANGUAGE_BY_EXTENSION[extension] ?? 'text',
      });
    } catch {
      failedCount += 1;
    }
  }

  if (fileList.length > MAX_FILES) {
    warnings.push(`一次最多读取 ${MAX_FILES} 个文件，其余文件已忽略。`);
  }
  if (unsupportedCount > 0) {
    warnings.push(`已忽略 ${unsupportedCount} 个二进制或暂不支持的文件。`);
  }
  if (oversizedCount > 0) {
    warnings.push(`已忽略 ${oversizedCount} 个超过 300 KB 的文件。`);
  }
  if (failedCount > 0) {
    warnings.push(`有 ${failedCount} 个文件读取失败，请重新选择。`);
  }

  return { files: acceptedFiles, warnings };
};

export const useProjectFiles = () => {
  const isReading = ref(false);
  const warnings = ref<string[]>([]);

  const selectFiles = async (fileList: FileList | null): Promise<ContextFileInput[]> => {
    if (!fileList || fileList.length === 0) {
      warnings.value = [];
      return [];
    }
    isReading.value = true;
    try {
      const selection = await readProjectFiles(fileList);
      warnings.value = selection.warnings;
      return selection.files;
    } finally {
      isReading.value = false;
    }
  };

  return {
    isReading: readonly(isReading),
    warnings: readonly(warnings),
    selectFiles,
  };
};
