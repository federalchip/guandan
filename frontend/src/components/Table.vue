<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'
import Card from './Card.vue'

type GameView = components['schemas']['GameView']

const props = defineProps<{ view: GameView }>()

const SEAT_LABEL = ['你（座位 0）', '下家（座位 1）', '对家（座位 2）', '上家（座位 3）']

const remaining = computed(() => props.view.remaining_counts ?? [])
const finishOrder = computed(() => props.view.finish_order ?? [])
const trickCards = computed(() => props.view.trick?.cards ?? [])

function placeOf(seat: number): number {
  const i = finishOrder.value.indexOf(seat)
  return i < 0 ? 0 : i + 1
}
</script>

<template>
  <div class="table">
    <div class="seats">
      <div
        v-for="(count, seat) in remaining"
        :key="seat"
        class="seat"
        :class="{ current: view.current_player === seat, finished: placeOf(seat) > 0 }"
      >
        <span class="seat-label">{{ SEAT_LABEL[seat] }}</span>
        <span class="seat-count">{{ count }} 张</span>
        <span v-if="placeOf(seat) > 0" class="seat-rank">第 {{ placeOf(seat) }} 名</span>
        <span v-else-if="view.current_player === seat" class="seat-turn">行动中</span>
      </div>
    </div>
    <div class="trick">
      <template v-if="view.trick">
        <span class="trick-owner">桌面 · 出牌者座位 {{ view.trick.owner_id }}</span>
        <div class="trick-cards">
          <Card v-for="c in trickCards" :key="c.card_id" :card="c" />
        </div>
      </template>
      <span v-else class="trick-empty">等待领出</span>
    </div>
  </div>
</template>

<style scoped>
.table {
  padding: 12px 16px;
}
.seats {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}
.seat {
  min-width: 120px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 14px;
}
.seat.current {
  outline: 2px solid #f59e0b;
}
.seat.finished {
  opacity: 0.7;
}
.seat-count {
  font-weight: 700;
}
.trick {
  margin-top: 16px;
  min-height: 90px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.trick-cards {
  display: flex;
  gap: 4px;
}
.trick-owner,
.trick-empty {
  font-size: 13px;
  opacity: 0.85;
}
</style>
