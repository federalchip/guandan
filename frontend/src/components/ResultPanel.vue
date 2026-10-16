<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '../api/schema'

type ResultView = components['schemas']['ResultView']

const props = defineProps<{ result: ResultView }>()

const OUTCOME: Record<string, string> = {
  DOUBLE_UP: '双下',
  TEAMMATE_THIRD: '队友三游',
  TEAMMATE_LAST: '队友末游',
}

const winnerLabel = computed(() => (props.result.winner_team === 0 ? '队伍 {0, 2}' : '队伍 {1, 3}'))
const outcomeLabel = computed(() => OUTCOME[props.result.outcome ?? ''] ?? props.result.outcome ?? '')
const groups = computed(() => props.result.placement_groups ?? [])
</script>

<template>
  <div class="result-panel">
    <h2>本局结束</h2>
    <p>胜方：{{ winnerLabel }}</p>
    <p>结果：{{ outcomeLabel }}</p>
    <ol class="placements">
      <li v-for="(group, i) in groups" :key="i">第 {{ i + 1 }} 名：座位 {{ group.join('、') }}</li>
    </ol>
  </div>
</template>

<style scoped>
.result-panel {
  margin: 16px auto;
  max-width: 420px;
  padding: 16px 20px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.1);
}
.result-panel h2 {
  margin: 0 0 8px;
  font-size: 20px;
}
.placements {
  margin: 8px 0 0;
  padding-left: 20px;
  line-height: 1.7;
}
</style>
