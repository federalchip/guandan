<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'
import { describeMove, seatName, SEAT_LABEL } from '../state/eventLabel'
import { tableFromSnapshot, type TablePresentation } from '../state/tablePresentation'
import Card from './Card.vue'

type GameView = components['schemas']['GameView']

const props = defineProps<{ view: GameView; seatActions?: TablePresentation }>()
const seats = [0, 1, 2, 3]
const seatDirections = ['bottom', 'left', 'top', 'right']
const actions = computed(() => props.seatActions ?? tableFromSnapshot(props.view))
const finishOrder = computed(() => props.view.finish_order ?? [])
const trickLabel = computed(() => describeMove(props.view.trick?.move))
const trickCards = computed(() => props.view.trick?.cards ?? [])
const trickBackCount = computed(() => Math.max(0, (props.view.trick?.move?.length ?? 0) - trickCards.value.length))

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
      :class="[`seat-${seat}`, `seat-${seatDirections[seat]}`, { current: view.current_player === seat, leading: view.trick?.owner_id === seat, finished: placeOf(seat) > 0 }]"
      :data-seat="seat"
    >
      <div class="seat-heading">
        <span class="seat-label">{{ SEAT_LABEL[seat] }}</span>
        <span class="seat-count">{{ view.remaining_counts?.[seat] ?? 0 }} 张</span>
      </div>
      <span v-if="placeOf(seat) > 0" class="seat-rank">第 {{ placeOf(seat) }} 名</span>
      <span v-else-if="view.current_player === seat" class="seat-turn">行动中</span>
      <div class="seat-play">
        <span v-if="actions[seat]?.passed" class="pass-marker">不出</span>
        <span v-if="actions[seat]?.move" class="move-label">{{ describeMove(actions[seat]?.move ?? undefined) }}</span>
        <div v-if="actions[seat]?.move" class="played-cards" tabindex="0" :aria-label="`${SEAT_LABEL[seat]}公开的牌，左右滑动查看更多`">
          <Card v-for="c in actions[seat]?.cards ?? []" :key="c.card_id" :card="c" />
          <div v-for="n in backCount(seat)" :key="`back-${seat}-${n}`" class="card-back" />
        </div>
        <span v-if="(actions[seat]?.move?.length ?? 0) > 5" class="scroll-hint">左右滑动查看全部</span>
      </div>
    </section>

    <div class="center">
      <div v-if="view.trick" class="trick-info">
        <span>{{ seatName(view.trick.owner_id) }}出的牌</span>
        <strong v-if="trickLabel">{{ trickLabel }}</strong>
      </div>
      <div v-if="view.trick" class="trick-cards" role="group" aria-label="当前有效的牌">
        <Card v-for="c in trickCards" :key="c.card_id" :card="c" />
        <div v-for="n in trickBackCount" :key="`trick-back-${n}`" class="card-back" />
      </div>
      <p v-else class="trick-empty">{{ view.current_player === 0 ? '轮到你领出' : `等待${seatName(view.current_player)}领出` }}</p>
    </div>
    <div class="table-actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped>
.table {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(320px, 1.6fr) minmax(240px, 1fr);
  grid-template-rows: 160px 190px 160px auto;
  gap: 10px;
  padding: 14px 16px;
  background: var(--felt);
  border: 1px solid rgba(250, 248, 241, 0.19);
  border-radius: 26px;
  box-shadow: inset 0 0 0 4px rgba(10, 56, 41, 0.22);
}
.seat {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 8px;
  border-radius: 12px;
  background: rgba(250, 248, 241, 0.035);
  border: 1px solid rgba(250, 248, 241, 0.11);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.seat-0 { grid-column: 2; grid-row: 3; }
.seat-1 { grid-column: 1; grid-row: 2; }
.seat-2 { grid-column: 2; grid-row: 1; }
.seat-3 { grid-column: 3; grid-row: 2; }
.seat.current { border-color: var(--gold); background: rgba(233, 182, 66, 0.1); }
.seat.leading { border-bottom: 3px solid var(--gold); }
.seat.finished { opacity: 0.72; }
.seat-heading { display: flex; align-items: center; gap: 6px; flex-wrap: nowrap; }
.seat-label { font-size: 16px; font-weight: 700; }
.seat-count { margin-left: auto; font-size: 13px; font-variant-numeric: tabular-nums; }
.seat-rank, .seat-turn { font-size: 12px; color: var(--gold); }
.seat-play { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.pass-marker { color: var(--gold); font-weight: 700; background: var(--felt-deep); padding: 3px 9px; border-radius: 6px; }
.move-label { font-size: 12px; opacity: 0.9; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scroll-hint { font-size: 11px; opacity: 0.9; }
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
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
  padding: 14px;
  background: var(--felt-deep);
  border: 1px solid rgba(233, 182, 66, 0.7);
  border-radius: 90px;
}
.trick-info { display: flex; flex-direction: column; gap: 5px; font-size: 13px; }
.trick-info strong { color: var(--gold); font-size: clamp(17px, 2.5vw, 26px); line-height: 1.3; }
.trick-cards { display: flex; flex-shrink: 0; max-width: 100%; overflow-x: auto; padding: 4px; scrollbar-color: var(--gold) var(--felt-deep); }
.trick-cards > * { flex: 0 0 52px; }
.trick-cards > * + * { margin-left: -8px; }
.trick-empty { font-size: 16px; margin: 0; }
.table-actions { grid-column: 1 / -1; grid-row: 4; min-width: 0; }
@media (max-width: 1000px) {
  .table {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: 146px 146px 184px 146px auto;
    gap: 8px;
    padding: 10px;
    border-radius: 18px;
  }
  .seat-2 { grid-column: 1 / -1; grid-row: 1; }
  .seat-1 { grid-column: 1; grid-row: 2; }
  .seat-3 { grid-column: 2; grid-row: 2; }
  .center { grid-column: 1 / -1; grid-row: 3; border-radius: 14px; }
  .seat-0 { grid-column: 1 / -1; grid-row: 4; }
  .table-actions { grid-column: 1 / -1; grid-row: 5; margin-top: 2px; }
}
@media (max-width: 640px) {
  .table { gap: 6px; padding: 8px 8px 0; }
  .seat { padding: 6px; gap: 3px; }
  .seat-heading { gap: 3px; }
  .seat-label { font-size: 13px; }
  .seat-count { margin-left: 0; font-size: 11px; }
  .seat-turn, .seat-rank, .move-label { font-size: 11px; }
  .center { gap: 5px; padding: 8px; }
  .trick-info { font-size: 11px; }
  .trick-info strong { font-size: 15px; }
  .trick-empty { font-size: 13px; }
}
@media (max-width: 560px) {
  .table {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 146px 146px 184px 146px 146px auto;
  }
  .seat-2 { grid-column: 1; grid-row: 1; }
  .seat-1 { grid-column: 1; grid-row: 2; }
  .center { grid-column: 1; grid-row: 3; }
  .seat-3 { grid-column: 1; grid-row: 4; }
  .seat-0 { grid-column: 1; grid-row: 5; }
  .table-actions { grid-column: 1; grid-row: 6; }
}
</style>
