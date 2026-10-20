<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'
import Card from './Card.vue'

type CardView = components['schemas']['CardView']

const props = defineProps<{ cards: CardView[]; selected: Set<number> }>()
const emit = defineEmits<{ (e: 'select', cardId: number): void }>()

// 手牌已按展示顺序传入；按点数归为竖列，不改变实体牌 ID 或组内顺序。
const groups = computed(() => {
  const byRank = new Map<number | undefined, CardView[]>()
  for (const card of props.cards) {
    const rank = card.rank
    if (!byRank.has(rank)) byRank.set(rank, [])
    byRank.get(rank)!.push(card)
  }
  return Array.from(byRank, ([rank, cards]) => ({ rank, cards }))
})
</script>

<template>
  <div class="hand">
    <div v-for="group in groups" :key="group.rank" class="rank-group">
      <Card
        v-for="c in group.cards"
        :key="c.card_id"
        :card="c"
        interactive
        :selected="selected.has(c.card_id as number)"
        @select="(id) => emit('select', id)"
      />
    </div>
  </div>
</template>

<style scoped>
.hand {
  display: flex;
  flex-wrap: nowrap;
  gap: 10px;
  padding: 18px 16px 28px;
  justify-content: safe center;
  align-items: flex-end;
  overflow-x: auto;
}
.rank-group {
  display: flex;
  flex-direction: column;
  flex: 0 0 52px;
  isolation: isolate;
}
/* 每张牌露出点数和花色，后面的牌覆盖前一张的下半部。 */
.rank-group > * {
  position: relative;
}
.rank-group > * + * {
  margin-top: -44px;
}
.rank-group :deep(.card) {
  flex-direction: row;
  align-items: flex-start;
  justify-content: center;
  gap: 2px;
  padding-top: 3px;
}
.rank-group :deep(.card.selected) {
  transform: none;
}
.rank-group :deep(.card:hover) {
  box-shadow: 0 0 0 2px #f59e0b;
}
</style>
