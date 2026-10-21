<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { createGameState } from './state/game'
import { sortHandForDisplay, LEVEL_RANK } from './state/handOrder'
import { describeEvent, seatName } from './state/eventLabel'
import { createGameApi } from './api/game'
import type { components } from './api/schema'
import Table from './components/Table.vue'
import Hand from './components/Hand.vue'
import ActionBar from './components/ActionBar.vue'
import ResultPanel from './components/ResultPanel.vue'
import DeclarationPicker from './components/DeclarationPicker.vue'

type EventView = components['schemas']['EventView']

const api = createGameApi()
const reduceMotion = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
// 播放当前事件的展示态：动画期间逐事件更新，驱动牌桌上的“逐步出牌”叙述。
const lastEvent = ref<EventView | null>(null)
// 生产环境开启动画：命令响应按 seq 逐事件播放，玩家能看到bot依次出牌/过牌，而非直接跳到最终快照。
const store = createGameState({
  api,
  animate: !reduceMotion,
  onEvent: (e) => {
    lastEvent.value = e
  },
})
// 动画收敛（display 落到最终快照）后撤下读屏播报，避免重复讲述已就位的牌桌。
watch(
  () => store.state.animating,
  (animating) => {
    if (!animating) lastEvent.value = null
  },
)

// 渲染展示状态（动画收敛目标）；空闲时等于权威快照。
const view = computed(() => store.state.display)
const started = computed(() => view.value !== null)
const finished = computed(() => view.value?.phase === 'FINISHED')
const ownHand = computed(() => sortHandForDisplay(view.value?.own_hand ?? [], view.value?.level_rank ?? LEVEL_RANK))
const selectedCount = computed(() => store.state.selected.size)
const result = computed(() => view.value?.result ?? null)
const notice = computed(() => store.state.notice)
const choices = computed(() => store.state.choices)
const canPass = computed(() => store.state.canPass)
// 结果不明（5xx/网络且 pending 仍在）或版本冲突重同步失败时暴露重试；正常在途请求不显示。
const canRetry = computed(() => store.state.awaitingRetry || store.state.needsResync)
const playback = computed(() => (store.state.animating && lastEvent.value ? describeEvent(lastEvent.value) : null))
// 仅当前局已同步（无 pending/动画/解析）、未结束且轮到真人时可操作。
const canAct = computed(
  () => started.value && !finished.value && !store.state.busy && view.value?.current_player === 0,
)
const turnStatus = computed(() => {
  if (!view.value) return canRetry.value ? '上一局结果待确认，请重试' : '准备开始'
  if (finished.value) return '本局结束'
  if (canRetry.value) return '上一步结果待确认'
  if (store.state.animating) return '正在展示本轮行动'
  if (store.state.busy) return '正在处理，请稍候'
  if (view.value.current_player === 0) return '轮到你出牌'
  return `等待${seatName(view.value.current_player)}出牌`
})

onMounted(() => store.recover())
</script>

<template>
  <div class="app">
    <header class="topbar">
      <h1>掼蛋</h1>
      <label class="anim-toggle">
        <input
          type="checkbox"
          :checked="store.state.animate"
          @change="store.setAnimate(($event.target as HTMLInputElement).checked)"
        />
        逐步播放
      </label>
    </header>
    <ActionBar
      v-if="!view"
      :started="started"
      :finished="finished"
      :can-act="canAct"
      :can-pass="canPass"
      :can-retry="canRetry"
      :selected-count="selectedCount"
      :turn-status="turnStatus"
      :notice="notice"
      @start="store.createGame"
      @restart="store.createGame"
      @play="store.playSelection"
      @pass="store.passTurn"
      @hint="store.cycleHint"
      @retry="store.retryPendingCommand"
    />
    <p v-if="playback" class="playback" role="status" aria-live="polite" data-testid="playback">{{ playback }}</p>
    <DeclarationPicker
      v-if="choices && choices.length"
      :choices="choices"
      @pick="store.pickDeclaration"
      @cancel="store.dismissDeclaration"
    />
    <template v-if="view">
      <Table :view="view" :seat-actions="store.state.table">
        <template #actions>
          <ActionBar
            :started="started"
            :finished="finished"
            :can-act="canAct"
            :can-pass="canPass"
            :can-retry="canRetry"
            :selected-count="selectedCount"
            :turn-status="turnStatus"
            :notice="notice"
            @start="store.createGame"
            @restart="store.createGame"
            @play="store.playSelection"
            @pass="store.passTurn"
            @hint="store.cycleHint"
            @retry="store.retryPendingCommand"
          />
        </template>
      </Table>
      <Hand :cards="ownHand" :selected="store.state.selected" @select="store.toggleSelected" />
      <ResultPanel v-if="finished && result" :result="result" />
    </template>
    <p v-else-if="!canRetry" class="hint">你和搭档对阵左右两家，使用两副牌、固定打 2。开始后，轮到你时选牌并出牌。</p>
  </div>
</template>

<style scoped>
.app {
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 12px 32px;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 8px 14px;
}
.topbar h1 {
  font-size: 24px;
  letter-spacing: 0.04em;
  margin: 0;
}
.anim-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}
.playback {
  position: absolute;
  left: -10000px;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: 0;
  overflow: hidden;
  white-space: nowrap;
}
.hint {
  max-width: 50ch;
  padding: 20px 8px;
  margin: 0;
  line-height: 1.7;
  color: rgba(248, 246, 237, 0.88);
}
@media (max-width: 640px) {
  .app { padding: 0 6px 24px; }
  .topbar { padding: 14px 10px 10px; }
  .topbar h1 { font-size: 21px; }
}
</style>
