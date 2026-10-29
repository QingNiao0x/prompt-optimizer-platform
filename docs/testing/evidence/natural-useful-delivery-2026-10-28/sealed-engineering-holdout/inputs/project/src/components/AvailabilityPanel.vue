<script setup lang="ts">
import type { AvailabilityQuote } from '../domain/types';
defineProps<{ quote: AvailabilityQuote | null; editing?: boolean }>();
</script>

<template>
  <section class="availability-panel" aria-label="器材余量">
    <template v-if="quote">
      <div><span>可借数量</span><strong data-test="available-count">{{ quote.available }}</strong></div>
      <dl>
        <div><dt>地点总库存</dt><dd data-test="total-count">{{ quote.total }}</dd></div>
        <div><dt>{{ editing ? '其他借用占用' : '当前借用占用' }}</dt><dd data-test="occupied-count">{{ quote.occupied }}</dd></div>
      </dl>
      <p>{{ editing ? '本单修改数量以当前可容纳的整单数量为准。' : '页面数字是预览，提交仍需检查最新余量。' }}</p>
    </template>
    <p v-else data-test="unknown-stock">该地点未配置此器材库存</p>
  </section>
</template>
