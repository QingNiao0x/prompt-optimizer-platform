import { computed, ref } from 'vue';
import { deskRuntime } from '../application/deskRuntime';
import { readableError } from '../domain/errors';
import type { AvailabilityQuote, LoanDesk } from '../domain/types';

export function useLoanEdit(desk: LoanDesk = deskRuntime) {
  const snapshot = ref(desk.snapshot());
  const loanId = ref('L101');
  const quantity = ref(2);
  const purpose = ref('社区短片记录');
  const error = ref('');
  const success = ref('');
  const revision = ref(0);

  const loan = computed(() => snapshot.value.loans.find((row) => row.id === loanId.value));
  const readonly = computed(() => loan.value?.status !== 'CONFIRMED');
  const quote = computed<AvailabilityQuote | null>(() => {
    void revision.value;
    try { return desk.quoteEdit(loanId.value); }
    catch { return null; }
  });
  const kit = computed(() => snapshot.value.kits.find((row) => row.id === loan.value?.kitId));
  const site = computed(() => snapshot.value.sites.find((row) => row.id === loan.value?.siteId));

  function selectLoan(value: string): void {
    loanId.value = value;
    const selected = desk.getLoan(value);
    quantity.value = selected.quantity;
    purpose.value = selected.purpose;
    error.value = '';
    success.value = '';
    revision.value += 1;
  }

  function refresh(): void {
    snapshot.value = desk.snapshot();
    revision.value += 1;
  }

  function submit(): void {
    error.value = '';
    success.value = '';
    try {
      const result = desk.updateLoan({ loanId: loanId.value, quantity: quantity.value, purpose: purpose.value });
      success.value = `已保存 ${result.id}，版本 ${result.revision}`;
      refresh();
    } catch (cause) {
      error.value = readableError(cause);
    }
  }

  return { snapshot, loanId, quantity, purpose, error, success, loan, readonly, quote, kit, site, selectLoan, refresh, submit };
}
