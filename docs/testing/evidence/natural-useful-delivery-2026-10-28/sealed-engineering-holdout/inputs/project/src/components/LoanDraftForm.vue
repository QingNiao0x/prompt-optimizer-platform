<script setup lang="ts">
import { quantityFromInput } from '../domain/quantity';
defineProps<{ quantity: number; purpose: string; readonly?: boolean; submitText: string }>();
const emit = defineEmits<{
  'update:quantity': [value: number];
  'update:purpose': [value: string];
  submit: [];
}>();

function updateQuantity(event: Event): void {
  emit('update:quantity', quantityFromInput((event.target as HTMLInputElement).value));
}

function updatePurpose(event: Event): void {
  emit('update:purpose', (event.target as HTMLTextAreaElement).value);
}
</script>

<template>
  <section class="draft-form">
    <label>借用数量
      <input data-test="quantity-input" type="number" min="1" step="1" :value="quantity"
        :disabled="readonly" @input="updateQuantity">
    </label>
    <label>用途备注
      <textarea data-test="purpose-input" rows="3" :value="purpose" :disabled="readonly"
        placeholder="简单说明用途，失败时保留当前草稿" @input="updatePurpose" />
    </label>
    <p v-if="readonly" class="notice" data-test="readonly-note">该状态只读，不能在此修改。</p>
    <button data-test="submit-loan" class="primary-button" type="button" :disabled="readonly" @click="emit('submit')">
      {{ submitText }}
    </button>
  </section>
</template>
