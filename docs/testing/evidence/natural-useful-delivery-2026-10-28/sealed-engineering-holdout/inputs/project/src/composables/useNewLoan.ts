import { computed, ref } from 'vue';
import { deskRuntime } from '../application/deskRuntime';
import { readableError } from '../domain/errors';
import type { AvailabilityQuote, LoanDesk, SiteId } from '../domain/types';

export function useNewLoan(desk: LoanDesk = deskRuntime) {
  const snapshot = ref(desk.snapshot());
  const siteId = ref<SiteId>('harbor');
  const kitId = ref('camera');
  const quantity = ref(1);
  const purpose = ref('');
  const error = ref('');
  const success = ref('');
  const revision = ref(0);

  const quote = computed<AvailabilityQuote | null>(() => {
    void revision.value;
    try { return desk.quoteNew(siteId.value, kitId.value); }
    catch { return null; }
  });
  const kit = computed(() => snapshot.value.kits.find((row) => row.id === kitId.value));
  const site = computed(() => snapshot.value.sites.find((row) => row.id === siteId.value));
  const ledger = computed(() => snapshot.value.loans.filter((row) => row.siteId === siteId.value && row.kitId === kitId.value));

  function refresh(): void {
    snapshot.value = desk.snapshot();
    revision.value += 1;
  }

  function submit(): void {
    error.value = '';
    success.value = '';
    try {
      const result = desk.createLoan({ siteId: siteId.value, kitId: kitId.value,
        quantity: quantity.value, purpose: purpose.value });
      success.value = `已创建 ${result.id}，${result.quantity} 份`;
      refresh();
    } catch (cause) {
      error.value = readableError(cause);
    }
  }

  return { snapshot, siteId, kitId, quantity, purpose, error, success, quote, kit, site, ledger, refresh, submit };
}
