<script setup lang="ts">
import type { Loan, LoanStatus } from '../domain/types';
defineProps<{ loans: Loan[] }>();
const labels: Record<LoanStatus, string> = {
  DRAFT: '草稿', CONFIRMED: '已确认', COLLECTED: '已领取', RETURNED: '已归还', CANCELLED: '已取消',
};
</script>

<template>
  <section class="ledger">
    <h3>当前地点与器材的台账</h3>
    <table>
      <thead><tr><th>单号</th><th>数量</th><th>状态</th><th>用途</th></tr></thead>
      <tbody>
        <tr v-for="loan in loans" :key="loan.id">
          <td>{{ loan.id }}</td><td>{{ loan.quantity }}</td><td>{{ labels[loan.status] }}</td><td>{{ loan.purpose }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="loans.length === 0">暂无借用记录。</p>
  </section>
</template>
