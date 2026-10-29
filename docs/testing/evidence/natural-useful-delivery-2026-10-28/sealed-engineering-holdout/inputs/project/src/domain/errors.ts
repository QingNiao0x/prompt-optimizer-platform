export type DeskErrorCode = 'INVALID_QUANTITY' | 'UNKNOWN_STOCK' | 'LOAN_NOT_FOUND'
  | 'READ_ONLY_LOAN' | 'INSUFFICIENT_STOCK';

const messages: Record<DeskErrorCode, string> = {
  INVALID_QUANTITY: '数量必须为正整数',
  UNKNOWN_STOCK: '该地点未配置此器材库存',
  LOAN_NOT_FOUND: '借用单不存在',
  READ_ONLY_LOAN: '只有已确认借用单可以修改',
  INSUFFICIENT_STOCK: '可借数量不足，请按最新余量调整',
};

export class DeskError extends Error {
  readonly code: DeskErrorCode;

  constructor(code: DeskErrorCode) {
    super(messages[code]);
    this.name = 'DeskError';
    this.code = code;
  }
}

export function readableError(error: unknown): string {
  return error instanceof Error ? error.message : '操作未完成，请重新检查输入';
}
