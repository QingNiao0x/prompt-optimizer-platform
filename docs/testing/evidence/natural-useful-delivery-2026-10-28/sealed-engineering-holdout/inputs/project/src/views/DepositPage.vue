<script setup lang="ts">
import { useDepositPreview } from '../composables/useDepositPreview';
const { snapshot, loanId, loan, kit, deposit, explanation } = useDepositPreview();
function changeLoan(event: Event): void { loanId.value = (event.target as HTMLSelectElement).value; }
</script>

<template>
  <article class="desk-page" data-test="deposit-page">
    <header><p class="eyebrow">独立只读业务</p><h1>押金预览</h1></header>
    <label>借用单<select data-test="deposit-select" :value="loanId" @change="changeLoan"><option v-for="row in snapshot.loans" :key="row.id" :value="row.id">{{ row.id }}</option></select></label>
    <section class="availability-panel"><h2>{{ kit?.name }}</h2><p>{{ loan?.quantity }} 份 × {{ kit?.depositPerUnit }} 元</p><strong data-test="deposit-value">{{ deposit.toFixed(2) }} 元</strong><p>{{ explanation }}</p></section>
    <p class="notice">本页没有支付按钮，不会创建交易或改变借用状态。</p>
  </article>
</template>
