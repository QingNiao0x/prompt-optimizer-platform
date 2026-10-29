<script setup lang="ts">
import AvailabilityPanel from '../components/AvailabilityPanel.vue';
import LoanDraftForm from '../components/LoanDraftForm.vue';
import LoanLedger from '../components/LoanLedger.vue';
import { useNewLoan } from '../composables/useNewLoan';
import type { LoanDesk, SiteId } from '../domain/types';

const props = defineProps<{ desk?: LoanDesk }>();
const { snapshot, siteId, kitId, quantity, purpose, error, success, quote, kit, ledger, refresh, submit } = useNewLoan(props.desk);

function changeSite(event: Event): void {
  siteId.value = (event.target as HTMLSelectElement).value as SiteId;
}
function changeKit(event: Event): void {
  kitId.value = (event.target as HTMLSelectElement).value;
}
</script>

<template>
  <article class="desk-page" data-test="new-page">
    <header><p class="eyebrow">当前业务 · 新建</p><h1>新建器材借用</h1><p>地点之间分别管理库存，确认前可调整用途。</p></header>
    <div class="selector-row">
      <label>地点<select data-test="site-select" :value="siteId" @change="changeSite"><option v-for="site in snapshot.sites" :key="site.id" :value="site.id">{{ site.name }}</option></select></label>
      <label>器材<select data-test="kit-select" :value="kitId" @change="changeKit"><option v-for="item in snapshot.kits" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <button type="button" data-test="refresh-new" @click="refresh">刷新余量</button>
    </div>
    <p class="description">{{ kit?.description }}</p>
    <div class="form-columns">
      <AvailabilityPanel :quote="quote" />
      <LoanDraftForm v-model:quantity="quantity" v-model:purpose="purpose" submit-text="确认借用" @submit="submit" />
    </div>
    <p v-if="error" role="alert" class="error" data-test="error-message">{{ error }}</p>
    <p v-if="success" role="status" class="success" data-test="success-message">{{ success }}</p>
    <LoanLedger :loans="ledger" />
  </article>
</template>
