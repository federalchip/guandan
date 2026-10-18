<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { createGameState } from './state/game'
import { sortHandForDisplay, LEVEL_RANK } from './state/handOrder'
import { describeEvent } from './state/eventLabel'
import { createGameApi } from './api/game'
import type { components } from './api/schema'
import Table from './components/Table.vue'
import Hand from './components/Hand.vue'
import ActionBar from './components/ActionBar.vue'
import ResultPanel from './components/ResultPanel.vue'
import DeclarationPicker from './components/DeclarationPicker.vue'

type EventView = components['schemas']['EventView']

const api = createGameApi()
// 播放当前事件的展示态：动画期间逐事件更新，驱动牌桌上的“逐步出牌”叙述。
const lastEvent = ref<EventView | null>(null)
// 生产环境开启动画：命令响应按 seq 逐事件播放，玩家能看到bot依次出牌/过牌，而非直接跳到最终快照。
const store = createGameState({
  api,
  animate: true,
  onEvent: (e) => {
    lastEvent.value = e
  },
})
// 动画收敛（display 落到最终快照）后撤下叙述条，避免与已就位的牌桌重复。
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
// 仅在“结果不明”（5xx/网络且 pending 仍在）时暴露重试；正常在途请求不显示，避免重复重试锁死。
const canRetry = computed(() => store.state.awaitingRetry)
const playback = computed(() => (store.state.animating && lastEvent.value ? describeEvent(lastEvent.value) : null))
// 仅当前局已同步（无 pending/动画/解析）、未结束且轮到真人时可操作。
const canAct = computed(
  () => started.value && !finished.value && !store.state.busy && view.value?.current_player === 0,
)

onMounted(() => store.recover())
</script>

<template>
  <div class="app">
    <header class="topbar">
      <h1>掼蛋 v1</h1>
      <label class="anim-toggle">
        <input
          type="checkbox"
          :checked="store.state.animate"
          @change="store.setAnimate(($event.target as HTMLInputElement).checked)"
        />
        动画
      </label>
    </header>
    <ActionBar
      :started="started"
      :finished="finished"
      :can-act="canAct"
      :can-pass="canPass"
      :can-retry="canRetry"
      :selected-count="selectedCount"
      @start="store.createGame"
      @restart="store.createGame"
      @play="store.playSelection"
      @pass="store.passTurn"
      @hint="store.cycleHint"
      @retry="store.retryPendingCommand"
    />
    <p v-if="notice" class="notice">{{ notice }}</p>
    <p v-if="playback" class="playback" data-testid="playback">{{ playback }}</p>
    <DeclarationPicker
      v-if="choices && choices.length"
      :choices="choices"
      @pick="store.pickDeclaration"
      @cancel="store.dismissDeclaration"
    />
    <template v-if="view">
      <Table :view="view" />
      <Hand :cards="ownHand" :selected="store.state.selected" @select="store.toggleSelected" />
      <ResultPanel v-if="finished && result" :result="result" />
    </template>
    <p v-else class="hint">点击「开始一局」发牌。</p>
  </div>
</template>

<style scoped>
.app {
  max-width: 960px;
  margin: 0 auto;
  padding-bottom: 24px;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
}
.topbar h1 {
  font-size: 20px;
  margin: 0;
}
.anim-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  cursor: pointer;
  user-select: none;
}
.notice {
  margin: 10px 16px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(220, 38, 38, 0.2);
  border: 1px solid #dc2626;
}
.playback {
  margin: 10px 16px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(245, 158, 11, 0.18);
  border: 1px solid #f59e0b;
  font-size: 14px;
}
.hint {
  padding: 24px 16px;
  text-align: center;
  opacity: 0.8;
}
</style>
