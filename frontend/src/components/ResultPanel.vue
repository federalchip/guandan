<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'
import { seatName } from '../state/eventLabel'

type ResultView = components['schemas']['ResultView']

const props = defineProps<{ result: ResultView }>()

const OUTCOME: Record<string, string> = {
  DOUBLE_UP: '双下',
  TEAMMATE_THIRD: '队友三游',
  TEAMMATE_LAST: '队友末游',
}

const winnerLabel = computed(() => (props.result.winner_team === 0 ? '你和搭档获胜' : '下家和上家获胜'))
const outcomeLabel = computed(() => OUTCOME[props.result.outcome ?? ''] ?? props.result.outcome ?? '')
const groups = computed(() => props.result.placement_groups ?? [])
</script>

<template>
  <div class="result-panel">
    <h2>本局结束</h2>
    <p class="winner">{{ winnerLabel }}</p>
    <p>结果：{{ outcomeLabel }}</p>
    <ol class="placements">
      <li v-for="(group, i) in groups" :key="i">第 {{ i + 1 }} 名：{{ group.map(seatName).join('、') }}</li>
    </ol>
  </div>
</template>

<style scoped>
.result-panel {
  margin: 12px 16px;
  padding: 20px 24px;
  border-left: 4px solid var(--gold);
  background: var(--felt);
  border-radius: 0 10px 10px 0;
}
.result-panel h2 {
  margin: 0 0 8px;
  font-size: 24px;
}
.winner { color: var(--gold); font-weight: 700; font-size: 19px; }
.placements {
  margin: 8px 0 0;
  padding-left: 20px;
  line-height: 1.7;
}
</style>
