import { computed, ref } from 'vue';
import { deskRuntime } from '../application/deskRuntime';
import type { LoanDesk } from '../domain/types';

/** 押金只读预览与借用余量无关，不承担支付或退款。 */
export function useDepositPreview(desk: LoanDesk = deskRuntime) {
  const snapshot = desk.snapshot();
  const loanId = ref('L101');
  const loan = computed(() => snapshot.loans.find((row) => row.id === loanId.value));
  const kit = computed(() => snapshot.kits.find((row) => row.id === loan.value?.kitId));
  const deposit = computed(() => (loan.value?.quantity ?? 0) * (kit.value?.depositPerUnit ?? 0));
  const explanation = computed(() => loan.value?.status === 'RETURNED'
    ? '已归还：请按原流程核对押金，不在此页面退款。'
    : '这里仅按套数显示参考押金，不代表支付状态。');
  return { snapshot, loanId, loan, kit, deposit, explanation };
}
