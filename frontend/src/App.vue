<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createGameState } from './state/game'
import { createGameApi, ApiError } from './api/game'
import Table from './components/Table.vue'
import Hand from './components/Hand.vue'
import ActionBar from './components/ActionBar.vue'
import ResultPanel from './components/ResultPanel.vue'

const STORAGE_KEY = 'guandan.session'

const store = createGameState()
const api = createGameApi()
const notice = ref<string | null>(null)

const view = computed(() => store.state.view)
const started = computed(() => view.value !== null)
const finished = computed(() => view.value?.phase === 'FINISHED')
const ownHand = computed(() => view.value?.own_hand ?? [])
const selectedCount = computed(() => store.state.selected.size)
const result = computed(() => view.value?.result ?? null)

function persist() {
  const { gameId, token } = store.state
  if (gameId && token) sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ game_id: gameId, token }))
}

function messageOf(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}：${e.message}`
  return e instanceof Error ? e.message : String(e)
}

async function startGame() {
  notice.value = null
  try {
    const created = await api.createGame()
    const { game_id: gameId, session_token: token, state } = created
    if (!gameId || !token || !state) throw new Error('创建响应缺少字段')
    store.clearGame()
    store.setSession(gameId, token)
    store.applySnapshot(gameId, state)
    persist()
  } catch (e) {
    notice.value = messageOf(e)
  }
}

async function recover() {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return
  let gameId = ''
  let token = ''
  try {
    const parsed = JSON.parse(raw) as { game_id?: string; token?: string }
    gameId = parsed.game_id ?? ''
    token = parsed.token ?? ''
  } catch {
    sessionStorage.removeItem(STORAGE_KEY)
    return
  }
  if (!gameId || !token) return
  try {
    const fresh = await api.getGame(gameId, token)
    store.clearGame()
    store.setSession(gameId, token)
    store.applySnapshot(gameId, fresh)
  } catch (e) {
    sessionStorage.removeItem(STORAGE_KEY)
    store.clearGame()
    notice.value = e instanceof ApiError && e.status === 404 ? '本局已失效，请重新开始' : messageOf(e)
  }
}

onMounted(recover)
</script>

<template>
  <div class="app">
    <header class="topbar">
      <h1>掼蛋 v1</h1>
    </header>
    <ActionBar :started="started" :selected-count="selectedCount" @start="startGame" @restart="startGame" />
    <p v-if="notice" class="notice">{{ notice }}</p>
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
