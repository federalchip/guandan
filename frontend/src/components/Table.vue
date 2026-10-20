<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'
import { describeMove } from '../state/eventLabel'
import { tableFromSnapshot, type TablePresentation } from '../state/tablePresentation'
import Card from './Card.vue'

type GameView = components['schemas']['GameView']

const props = defineProps<{ view: GameView; seatActions?: TablePresentation }>()
const SEAT_LABEL = ['你', '下家', '对家 · 搭档', '上家']
const seats = [0, 1, 2, 3]
const seatDirections = ['bottom', 'left', 'top', 'right']
const actions = computed(() => props.seatActions ?? tableFromSnapshot(props.view))
const finishOrder = computed(() => props.view.finish_order ?? [])
const trickLabel = computed(() => describeMove(props.view.trick?.move))

function placeOf(seat: number): number {
  const i = finishOrder.value.indexOf(seat)
  return i < 0 ? 0 : i + 1
}

function backCount(seat: number): number {
  const action = actions.value[seat]
  return Math.max(0, (action?.move?.length ?? 0) - (action?.cards.length ?? 0))
}
</script>

<template>
  <div class="table">
    <section
      v-for="seat in seats"
      :key="seat"
      class="seat"
      :class="[`seat-${seat}`, `seat-${seatDirections[seat]}`, { current: view.current_player === seat, finished: placeOf(seat) > 0 }]"
      :data-seat="seat"
    >
      <div class="seat-heading">
        <span class="seat-avatar">{{ seat }}</span>
        <span class="seat-label">{{ SEAT_LABEL[seat] }}</span>
        <span class="seat-count">{{ view.remaining_counts?.[seat] ?? 0 }} 张</span>
      </div>
      <span v-if="placeOf(seat) > 0" class="seat-rank">第 {{ placeOf(seat) }} 名</span>
      <span v-else-if="view.current_player === seat" class="seat-turn">行动中</span>
      <div class="seat-play">
        <span v-if="actions[seat]?.passed" class="pass-marker">不出</span>
        <span v-if="actions[seat]?.move" class="move-label">{{ describeMove(actions[seat]?.move ?? undefined) }}</span>
        <div v-if="actions[seat]?.move" class="played-cards">
          <Card v-for="c in actions[seat]?.cards ?? []" :key="c.card_id" :card="c" />
          <div v-for="n in backCount(seat)" :key="`back-${seat}-${n}`" class="card-back" />
        </div>
      </div>
    </section>

    <div class="center">
      <div v-if="view.trick" class="trick-info">
        <span>当前有效 · 出牌者座位 {{ view.trick.owner_id }}</span>
        <strong v-if="trickLabel">{{ trickLabel }}</strong>
      </div>
      <p v-else class="trick-empty">等待领出</p>
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.table {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) minmax(260px, 2.2fr) minmax(140px, 1fr);
  grid-template-rows: minmax(120px, auto) minmax(190px, auto) minmax(120px, auto);
  gap: 10px;
  padding: 14px 16px;
  min-height: 460px;
}
.seat {
  min-width: 0;
  padding: 9px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.09);
  border: 1px solid rgba(255, 255, 255, 0.17);
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.seat-0 { grid-column: 2; grid-row: 3; }
.seat-1 { grid-column: 1; grid-row: 2; }
.seat-2 { grid-column: 2; grid-row: 1; }
.seat-3 { grid-column: 3; grid-row: 2; }
.seat.current { border-color: #fbbf24; box-shadow: 0 0 0 2px rgba(251, 191, 36, 0.45); }
.seat.finished { opacity: 0.75; }
.seat-heading { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.seat-avatar {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #f59e0b;
  color: #1f2937;
  font-weight: 700;
}
.seat-label { font-weight: 700; }
.seat-count { margin-left: auto; font-size: 13px; }
.seat-rank, .seat-turn { font-size: 12px; color: #fde68a; }
.seat-play { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.pass-marker { color: #fde68a; font-weight: 700; background: rgba(0, 0, 0, 0.3); padding: 2px 9px; border-radius: 12px; }
.move-label { font-size: 12px; opacity: 0.9; }
.played-cards { display: flex; max-width: 100%; overflow-x: auto; padding: 3px 3px 5px; }
.played-cards > * { flex: 0 0 52px; position: relative; }
.played-cards > * + * { margin-left: -8px; }
.played-cards :deep(.card) { flex-direction: row; justify-content: flex-start; align-items: flex-start; padding: 3px; gap: 2px; }
.card-back {
  width: 52px;
  height: 74px;
  border-radius: 6px;
  border: 1px solid #475569;
  background: repeating-linear-gradient(45deg, #1e293b, #1e293b 6px, #273449 6px, #273449 12px);
}
.center {
  grid-column: 2;
  grid-row: 2;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  text-align: center;
}
.trick-info { display: flex; flex-direction: column; font-size: 13px; }
.trick-info strong { color: #fbbf24; }
.trick-empty { font-size: 13px; opacity: 0.8; }
@media (max-width: 640px) {
  .table {
    grid-template-columns: minmax(88px, 1fr) minmax(140px, 1.7fr) minmax(88px, 1fr);
    gap: 5px;
    padding: 10px 6px;
  }
  .seat { padding: 5px; }
  .seat-count { margin-left: 0; }
}
</style>
