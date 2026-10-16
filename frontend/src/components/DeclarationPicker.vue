<script setup lang="ts">
import type { components } from '../api/schema'
import { KIND_LABEL } from '../state/eventLabel'

type MoveView = components['schemas']['MoveView']

defineProps<{ choices: MoveView[] }>()
const emit = defineEmits<{ (e: 'pick', interpretationKey: string): void; (e: 'cancel'): void }>()

const RANK_LABEL: Record<number, string> = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A', 15: '小王', 16: '大王' }

function rankLabel(rank: number | undefined): string {
  if (rank == null) return ''
  return RANK_LABEL[rank] ?? String(rank)
}

function describe(m: MoveView): string {
  const parts = [KIND_LABEL[m.kind ?? ''] ?? m.kind ?? '未知牌型']
  if (m.length != null) parts.push(`${m.length} 张`)
  const r = rankLabel(m.main_rank)
  if (r) parts.push(`主点 ${r}`)
  return parts.join(' · ')
}
</script>

<template>
  <div class="picker" role="dialog" aria-label="选择声明">
    <p class="title">所选牌有多种声明，请选择一种：</p>
    <ul class="options">
      <li v-for="(m, i) in choices" :key="m.interpretation_key ?? i">
        <button type="button" :data-key="m.interpretation_key" @click="emit('pick', m.interpretation_key ?? '')">
          {{ describe(m) }}
        </button>
      </li>
    </ul>
    <button type="button" class="cancel" @click="emit('cancel')">取消</button>
  </div>
</template>

<style scoped>
.picker {
  margin: 12px auto;
  max-width: 460px;
  padding: 14px 18px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid #f59e0b;
}
.title {
  margin: 0 0 10px;
  font-size: 14px;
}
.options {
  list-style: none;
  margin: 0 0 10px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.options button {
  width: 100%;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid #f59e0b;
  background: #f59e0b;
  color: #1f2937;
  font-weight: 700;
  cursor: pointer;
}
.cancel {
  padding: 6px 14px;
  border-radius: 6px;
  border: 1px solid #94a3b8;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
</style>
