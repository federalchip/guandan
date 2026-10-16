<script setup lang="ts">
import type { components } from '../api/schema'
import Card from './Card.vue'

type CardView = components['schemas']['CardView']

defineProps<{ cards: CardView[]; selected: Set<number> }>()
const emit = defineEmits<{ (e: 'select', cardId: number): void }>()
</script>

<template>
  <div class="hand">
    <Card
      v-for="c in cards"
      :key="c.card_id"
      :card="c"
      interactive
      :selected="selected.has(c.card_id as number)"
      @select="(id) => emit('select', id)"
    />
  </div>
</template>

<style scoped>
.hand {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 16px;
  justify-content: center;
}
/* 27 张重叠布局：负外边距让牌横向叠放，仍逐张可点选 */
.hand > * {
  margin-right: -18px;
}
.hand > *:hover {
  transform: translateY(-6px);
  z-index: 1;
}
</style>
