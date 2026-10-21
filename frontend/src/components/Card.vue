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
const SUIT_NAME: Record<string, string> = { S: '黑桃', H: '红桃', D: '方块', C: '梅花' }

const rankLabel = computed(() => {
  const r = props.card.rank
  if (r == null) return ''
  return RANK_LABEL[r] ?? String(r)
})
const isJoker = computed(() => props.card.rank === 15 || props.card.rank === 16)
const suitSymbol = computed(() => (isJoker.value || !props.card.suit ? '' : SUIT_SYMBOL[props.card.suit] ?? props.card.suit))
const isRed = computed(() => props.card.suit === 'H' || props.card.suit === 'D' || props.card.rank === 16)
const accessibleName = computed(() => {
  const deck = props.card.deck_index ?? (typeof props.card.card_id === 'number' ? Math.floor(props.card.card_id / 54) : 0)
  const face = props.card.rank != null && props.card.rank >= 15
    ? rankLabel.value
    : `${SUIT_NAME[props.card.suit ?? ''] ?? ''}${rankLabel.value}`
  return `第${deck + 1}副${face}`
})

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
    :class="{ selected, red: isRed, joker: isJoker }"
    :data-card-id="card.card_id"
    :aria-label="accessibleName"
    :aria-pressed="selected"
    @click="onClick"
  >
    <span class="rank">{{ rankLabel }}</span>
    <span v-if="suitSymbol" class="suit">{{ suitSymbol }}</span>
  </button>
  <div v-else class="card static" :class="{ red: isRed, joker: isJoker }" :data-card-id="card.card_id">
    <span class="rank">{{ rankLabel }}</span>
    <span v-if="suitSymbol" class="suit">{{ suitSymbol }}</span>
  </div>
</template>

<style scoped>
.card {
  width: 52px;
  height: 74px;
  border-radius: 6px;
  border: 1px solid #bcb8ad;
  background: var(--card);
  color: var(--ink);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-family: Georgia, "Times New Roman", serif;
  font-size: 19px;
  line-height: 1.1;
  cursor: pointer;
  padding: 0;
}
.card.static {
  cursor: default;
}
.card.red {
  color: var(--danger);
}
.card.selected {
  outline: 3px solid var(--gold);
  transform: translateY(-10px);
}
.card:focus-visible { position: relative; z-index: 1; }
.rank {
  font-weight: 700;
  white-space: nowrap;
}
.card.joker .rank { font-family: "Microsoft YaHei UI", "PingFang SC", sans-serif; font-size: 14px; }
.suit {
  font-size: 20px;
}
</style>
