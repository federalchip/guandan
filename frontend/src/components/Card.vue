<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'

type CardView = components['schemas']['CardView']

const props = withDefaults(defineProps<{ card: CardView; selected?: boolean; interactive?: boolean }>(), {
  selected: false,
  interactive: false,
})
const emit = defineEmits<{ (e: 'select', cardId: number): void }>()

const RANK_LABEL: Record<number, string> = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A', 15: '小王', 16: '大王' }
const SUIT_SYMBOL: Record<string, string> = { S: '♠', H: '♥', D: '♦', C: '♣' }

const rankLabel = computed(() => {
  const r = props.card.rank
  if (r == null) return ''
  return RANK_LABEL[r] ?? String(r)
})
const suitSymbol = computed(() => (props.card.suit ? SUIT_SYMBOL[props.card.suit] ?? props.card.suit : '🃏'))
const isRed = computed(() => props.card.suit === 'H' || props.card.suit === 'D' || props.card.rank === 16)

function onClick() {
  const id = props.card.card_id
  if (id != null) emit('select', id)
}
</script>

<template>
  <button
    v-if="interactive"
    type="button"
    class="card"
    :class="{ selected, red: isRed }"
    :data-card-id="card.card_id"
    @click="onClick"
  >
    <span class="rank">{{ rankLabel }}</span>
    <span class="suit">{{ suitSymbol }}</span>
  </button>
  <div v-else class="card static" :class="{ red: isRed }" :data-card-id="card.card_id">
    <span class="rank">{{ rankLabel }}</span>
    <span class="suit">{{ suitSymbol }}</span>
  </div>
</template>

<style scoped>
.card {
  width: 52px;
  height: 74px;
  border-radius: 6px;
  border: 1px solid #94a3b8;
  background: #fff;
  color: #0f172a;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  line-height: 1.1;
  cursor: pointer;
  padding: 0;
}
.card.static {
  cursor: default;
}
.card.red {
  color: #dc2626;
}
.card.selected {
  outline: 3px solid #f59e0b;
  transform: translateY(-10px);
}
.rank {
  font-weight: 700;
}
.suit {
  font-size: 20px;
}
</style>
