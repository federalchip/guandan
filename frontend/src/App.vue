<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { createGameState } from './state/game'
import { sortHandForDisplay, LEVEL_RANK } from './state/handOrder'
import { createGameApi } from './api/game'
import Table from './components/Table.vue'
import Hand from './components/Hand.vue'
import ActionBar from './components/ActionBar.vue'
import ResultPanel from './components/ResultPanel.vue'
import DeclarationPicker from './components/DeclarationPicker.vue'

const api = createGameApi()
const store = createGameState({ api })

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
const canRetry = computed(() => store.state.pending !== null)
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
.topbar h1 {
  font-size: 20px;
  padding: 12px 16px;
  margin: 0;
}
.notice {
  margin: 10px 16px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(220, 38, 38, 0.2);
  border: 1px solid #dc2626;
}
.hint {
  padding: 24px 16px;
  text-align: center;
  opacity: 0.8;
}
</style>
