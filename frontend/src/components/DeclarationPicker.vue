<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { components } from '../api/schema'
import { KIND_LABEL } from '../state/eventLabel'

type MoveView = components['schemas']['MoveView']

defineProps<{ choices: MoveView[] }>()
const emit = defineEmits<{ (e: 'pick', interpretationKey: string): void; (e: 'cancel'): void }>()

const RANK_LABEL: Record<number, string> = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A', 15: '小王', 16: '大王' }
const dialog = ref<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null

onMounted(async () => {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  dialog.value?.querySelector<HTMLButtonElement>('.options button')?.focus()
})

onBeforeUnmount(() => {
  const canReturn = returnFocus?.isConnected && !(returnFocus instanceof HTMLButtonElement && returnFocus.disabled)
  if (canReturn) returnFocus?.focus()
  else document.querySelector<HTMLElement>('[aria-label="我的手牌"]')?.focus()
})

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('cancel')
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const buttons = Array.from(dialog.value.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
  if (!buttons.length) return
  const first = buttons[0]!
  const last = buttons[buttons.length - 1]!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

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
  <div class="picker-overlay">
    <div ref="dialog" class="picker" role="dialog" aria-modal="true" aria-label="选择声明" @keydown="onKeydown">
      <p class="title">这组牌有多种出法，请选择牌型</p>
      <ul class="options">
        <li v-for="(m, i) in choices" :key="m.interpretation_key ?? i">
          <button type="button" :data-key="m.interpretation_key" @click="emit('pick', m.interpretation_key ?? '')">
            {{ describe(m) }}
          </button>
        </li>
      </ul>
      <button type="button" class="cancel" @click="emit('cancel')">取消选牌型</button>
    </div>
  </div>
</template>

<style scoped>
.picker-overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  padding: 16px;
  background: rgba(3, 25, 17, 0.76);
}
.picker {
  width: min(100%, 460px);
  max-width: 460px;
  max-height: min(80vh, 620px);
  overflow-y: auto;
  padding: 20px;
  border-radius: 14px;
  background: var(--felt-deep);
  border: 1px solid var(--gold);
}
.title {
  margin: 0 0 16px;
  font-size: 17px;
  font-weight: 700;
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
  min-height: 44px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--gold);
  background: var(--gold);
  color: var(--ink);
  font-weight: 700;
  cursor: pointer;
}
.cancel {
  min-height: 44px;
  padding: 8px 14px;
  border-radius: 8px;
  border: 1px solid var(--text);
  background: transparent;
  color: inherit;
  cursor: pointer;
}
</style>
