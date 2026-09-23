export interface DroppedFileCollection {
  files: File[];
  hasDirectory: boolean;
}

interface FileSystemFileEntryLike {
  isFile: true;
  isDirectory: false;
  name: string;
  file: (success: (file: File) => void, error?: (error: DOMException) => void) => void;
}

interface FileSystemDirectoryReaderLike {
  readEntries: (
    success: (entries: FileSystemEntryLike[]) => void,
    error?: (error: DOMException) => void,
  ) => void;
}

interface FileSystemDirectoryEntryLike {
  isFile: false;
  isDirectory: true;
  name: string;
  createReader: () => FileSystemDirectoryReaderLike;
}

type FileSystemEntryLike = FileSystemFileEntryLike | FileSystemDirectoryEntryLike;

interface DataTransferItemLike {
  kind: string;
  webkitGetAsEntry?: () => FileSystemEntryLike | null;
}

/**
 * 读取拖入的文件或目录。目录通过 Entry API 递归展开，避免浏览器执行默认导航。
 */
export const collectDroppedFiles = async (
  dataTransfer: DataTransfer,
): Promise<DroppedFileCollection> => {
  const items = Array.from(dataTransfer.items) as DataTransferItemLike[];
  const entries = items
    .filter((item) => item.kind === 'file')
    .map((item) => item.webkitGetAsEntry?.() ?? null)
    .filter((entry): entry is FileSystemEntryLike => entry !== null);

  if (entries.length === 0) {
    return { files: Array.from(dataTransfer.files), hasDirectory: false };
  }

  const files: File[] = [];
  let hasDirectory = false;
  for (const entry of entries) {
    hasDirectory ||= entry.isDirectory;
    await readEntry(entry, '', files);
  }
  return { files, hasDirectory };
};

const readEntry = async (
  entry: FileSystemEntryLike,
  parentPath: string,
  files: File[],
): Promise<void> => {
  const relativePath = parentPath ? `${parentPath}/${entry.name}` : entry.name;
  if (entry.isFile) {
    files.push(withRelativePath(await readFileEntry(entry), relativePath));
    return;
  }

  const reader = entry.createReader();
  while (true) {
    const entries = await readDirectoryBatch(reader);
    if (entries.length === 0) {
      return;
    }
    for (const child of entries) {
      await readEntry(child, relativePath, files);
    }
  }
};

const readFileEntry = (entry: FileSystemFileEntryLike): Promise<File> =>
  new Promise((resolve, reject) => entry.file(resolve, reject));

const readDirectoryBatch = (reader: FileSystemDirectoryReaderLike): Promise<FileSystemEntryLike[]> =>
  new Promise((resolve, reject) => reader.readEntries(resolve, reject));

export const withRelativePath = (file: File, relativePath: string): File => {
  try {
    Object.defineProperty(file, 'webkitRelativePath', { configurable: true, value: relativePath });
    return file;
  } catch {
    const copy = new File([file], file.name, { lastModified: file.lastModified, type: file.type });
    Object.defineProperty(copy, 'webkitRelativePath', { configurable: true, value: relativePath });
    return copy;
  }
};
