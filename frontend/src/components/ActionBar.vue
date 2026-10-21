<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'

const props = defineProps<{
  started: boolean
  finished: boolean
  canAct: boolean // 已同步、无 pending/动画、且轮到真人
  canPass: boolean
  canRetry: boolean // 结果不明或需重同步：可原样重试/重新拉取快照（未开始态也需可见）
  selectedCount: number
  turnStatus: string
  notice?: string | null
}>()
const emit = defineEmits<{
  (e: 'start'): void
  (e: 'restart'): void
  (e: 'play'): void
  (e: 'pass'): void
  (e: 'hint'): void
  (e: 'retry'): void
}>()

const confirmingRestart = ref(false)
const restartButton = ref<HTMLButtonElement | null>(null)
const confirmRestartButton = ref<HTMLButtonElement | null>(null)
const actionHint = computed(() => {
  if (!props.started || props.finished) return ''
  if (props.canRetry) return '上一步结果待确认，请重试。'
  if (!props.canAct) return '请等待当前行动完成。'
  if (props.selectedCount === 0) return props.canPass ? '先从手牌中选牌，或选择过牌。' : '先从手牌中选牌；领出时不能过牌。'
  return props.canPass ? '可以出牌或过牌。' : '领出时不能过牌。'
})

async function onRestart() {
  if (props.finished) {
    emit('restart')
  } else {
    confirmingRestart.value = true
    await nextTick()
    confirmRestartButton.value?.focus()
  }
}

async function cancelRestart() {
  confirmingRestart.value = false
  await nextTick()
  restartButton.value?.focus()
}

function confirmRestart() {
  confirmingRestart.value = false
  emit('restart')
}
</script>

<template>
  <div class="action-bar">
    <div class="status-group">
      <p class="turn-status" role="status" aria-live="polite">{{ turnStatus }}</p>
      <div class="feedback" :tabindex="notice ? 0 : -1">
        <p v-if="notice" class="notice" role="alert">{{ notice }}</p>
        <p v-else-if="actionHint" class="action-hint">{{ actionHint }}</p>
      </div>
    </div>
    <div class="buttons">
      <button v-if="canRetry" type="button" class="retry" @click="emit('retry')">重试</button>
      <button v-if="!started" type="button" @click="emit('start')">开始一局</button>
      <template v-else>
        <button type="button" :disabled="!canAct || selectedCount === 0" @click="emit('play')">出牌</button>
        <button type="button" :disabled="!canAct || !canPass" @click="emit('pass')">过牌</button>
        <button type="button" :disabled="!canAct" @click="emit('hint')">提示</button>
        <button ref="restartButton" type="button" class="restart" @click="onRestart">{{ finished ? '再来一局' : '重新开局' }}</button>
        <span class="sel-count">已选 {{ selectedCount }} 张</span>
      </template>
    </div>
    <div v-if="confirmingRestart" class="restart-confirm" role="group" aria-label="确认重新开局">
      <p>结束当前局并重新开始？</p>
      <button ref="confirmRestartButton" type="button" class="confirm-restart" @click="confirmRestart">结束并重新开始</button>
      <button type="button" class="cancel-restart" @click="cancelRestart">继续本局</button>
    </div>
  </div>
</template>

<style scoped>
.action-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--felt-deep);
  border-radius: 14px;
}
.status-group { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.turn-status, .action-hint { margin: 0; }
.turn-status { font-size: 17px; font-weight: 700; }
.action-hint { font-size: 13px; opacity: 0.9; }
.feedback { height: 68px; overflow-y: auto; }
.notice {
  margin: 0;
  padding: 6px 8px;
  font-size: 13px;
  line-height: 1.35;
  border-radius: 8px;
  background: rgba(183, 53, 63, 0.2);
  border: 1px solid var(--danger);
}
.buttons {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
button {
  min-height: 44px;
  padding: 8px 16px;
  border-radius: 8px;
  border: 1px solid var(--gold);
  background: var(--gold);
  color: var(--ink);
  font-weight: 700;
  cursor: pointer;
}
button:disabled {
  border-color: rgba(250, 248, 241, 0.35);
  background: rgba(250, 248, 241, 0.12);
  color: rgba(250, 248, 241, 0.72);
  cursor: not-allowed;
}
button.restart {
  background: transparent;
  color: var(--gold);
}
button.retry {
  background: var(--danger);
  border-color: var(--danger);
  color: #fff;
}
.sel-count { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.restart-confirm { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.restart-confirm p { margin: 0 8px 0 0; }
.restart-confirm .confirm-restart { background: var(--danger); border-color: var(--danger); color: #fff; }
.restart-confirm .cancel-restart { background: transparent; color: #fff; border-color: currentColor; }
@media (max-width: 1000px) {
  .action-bar { display: block; }
  .buttons { margin-top: 8px; }
}
@media (max-width: 640px) {
  .action-bar { display: block; padding: 12px; }
  .turn-status { font-size: 16px; }
  .buttons { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 12px; }
  .buttons button { width: 100%; }
  .buttons .sel-count { order: -1; grid-column: 1 / -1; }
  .restart-confirm { display: grid; grid-template-columns: 1fr 1fr; margin-top: 12px; }
  .restart-confirm p { grid-column: 1 / -1; }
  .restart-confirm button { width: 100%; }
}
</style>
