<script setup lang="ts">
import AvailabilityPanel from '../components/AvailabilityPanel.vue';
import LoanDraftForm from '../components/LoanDraftForm.vue';
import { useLoanEdit } from '../composables/useLoanEdit';
import type { LoanDesk } from '../domain/types';

const props = defineProps<{ desk?: LoanDesk }>();
const { snapshot, loanId, quantity, purpose, error, success, loan, readonly, quote, kit, site, selectLoan, refresh, submit } = useLoanEdit(props.desk);

function changeLoan(event: Event): void {
  selectLoan((event.target as HTMLSelectElement).value);
}
</script>

<template>
  <article class="desk-page" data-test="edit-page">
    <header><p class="eyebrow">当前业务 · 修改</p><h1>修改已确认借用</h1><p>保存整单新数量，不是在旧数量上追加。</p></header>
    <div class="selector-row">
      <label>借用单<select data-test="loan-select" :value="loanId" @change="changeLoan"><option v-for="row in snapshot.loans" :key="row.id" :value="row.id">{{ row.id }} · {{ row.purpose }}</option></select></label>
      <button type="button" data-test="refresh-edit" @click="refresh">刷新余量</button>
    </div>
    <p class="description" data-test="editing-context">{{ site?.name }} · {{ kit?.name }} · 当前数量 {{ loan?.quantity }}</p>
    <div class="form-columns">
      <AvailabilityPanel :quote="quote" editing />
      <LoanDraftForm v-model:quantity="quantity" v-model:purpose="purpose" :readonly="readonly" submit-text="保存修改" @submit="submit" />
    </div>
    <p v-if="error" role="alert" class="error" data-test="error-message">{{ error }}</p>
    <p v-if="success" role="status" class="success" data-test="success-message">{{ success }}</p>
  </article>
</template>
